// The Mini toolchain without any I/O of its own: check and compile on top of already loaded assets.
// Used by the module worker (browser) and by scripts/wasm/test-worker.mjs (node). The contract is
// website/.vitepress/theme/playground/types.ts (Diagnostic, CompileResult).
import { runCommand } from './memwasi.mjs'

const enc = new TextEncoder()
const WORK = '/work'

/**
 * @param {object} a
 * @param {WebAssembly.Module} a.minic        compiled minic.wasm
 * @param {{path: string, text: string}[]} [a.stdlib]  legacy stdlib sources in compilation order
 * @param {string} [a.stdlibMlib]                    precompiled stdlib MIR
 * @param {(args: string[], files: object, options: object) => Promise<object>} [a.clang]   `commands.clang` of @yowasp/clang (level full)
 * @param {Uint8Array} [a.libminirt]          libminirt-wasm32.a
 * @param {Uint8Array} [a.entry]              minirt-entry-wasm32.o
 * @param {{name: string, packages: string[], files?: {path: string, text: string}[], mlib?: string}[]} [a.libs]
 *        preinstalled libraries (libs.json): source entries support legacy manifests; mlib entries link precompiled MIR
 */
export function createCore({ minic, stdlib = [], stdlibMlib, clang, libminirt, entry, libs = [] }) {
    const stdlibPaths = stdlib.map(f => `${WORK}/stdlib/${f.path}`)
    const stdlibBytes = stdlib.map(f => enc.encode(f.text))
    const stdlibMlibPath = `${WORK}/stdlib.mlib`
    const stdlibMlibBytes = stdlibMlib ? enc.encode(stdlibMlib) : null
    const libMlibBytes = new Map(libs.filter(l => typeof l.mlib === 'string').map(l => [l.name, enc.encode(l.mlib)]))

    const safeName = n => n.replace(/^\/+/, '').replace(/\.\.\//g, '')

    const escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const libMatchers = libs.map(l => new RegExp(`(?<![\\w.])(?:${l.packages.map(escape).join('|')})(?![\\w])`))

    function requiredLibraryNames(files) {
        const wanted = new Set()
        libs.forEach((l, i) => { if (files.some(f => libMatchers[i].test(f.text))) wanted.add(l.name) })
        const byName = new Map(libs.map(l => [l.name, l]))
        const includeDependencies = name => {
            const lib = byName.get(name)
            for (const dep of lib?.dependencies ?? []) if (!wanted.has(dep)) {
                wanted.add(dep)
                includeDependencies(dep)
            }
        }
        for (const name of [...wanted]) includeDependencies(name)
        return libs.filter(l => wanted.has(l.name)).map(l => l.name)
    }

    /** Library files the program needs, as [path, text] pairs below /work/libs. */
    function libFilesFor(files) {
        const out = []
        // pack-mlibs writes dependency-first order; preserve it for the compiler's --lib validation.
        for (const name of requiredLibraryNames(files)) {
            const lib = libs.find(l => l.name === name)
            if (libMlibBytes.has(name)) out.push([`${WORK}/libs/${name}.mlib`, libMlibBytes.get(name)])
            else if (lib.path) throw new Error(`preinstalled library '${name}' was not loaded`)
            else for (const f of lib.files ?? []) out.push([`${WORK}/libs/${name}/${f.path}`, f.text])
        }
        return out
    }

    async function runMinic(files, flags, extraOut = []) {
        const fs = new Map()
        stdlib.forEach((f, i) => fs.set(stdlibPaths[i], stdlibBytes[i]))
        if (stdlibMlibBytes) fs.set(stdlibMlibPath, stdlibMlibBytes)
        const userPaths = files.map(f => `${WORK}/${safeName(f.name)}`)
        files.forEach((f, i) => fs.set(userPaths[i], enc.encode(f.text)))
        const libArgs = []
        const legacyLibPaths = []
        if (stdlibMlib) libArgs.push('--lib', stdlibMlibPath)
        for (const [path, text] of libFilesFor(files)) {
            fs.set(path, text instanceof Uint8Array ? text : enc.encode(text))
            if (path.endsWith('.mlib')) libArgs.push('--lib', path)
            else legacyLibPaths.push(path)
        }
        const sourceStdlibArgs = stdlibMlib ? [] : ['--stdlib', ...stdlibPaths]
        const r = await runCommand(minic, ['minic', ...flags, ...userPaths, ...legacyLibPaths, ...libArgs, ...sourceStdlibArgs], fs)
        const dec = new TextDecoder()
        const outputs = {}
        for (const p of extraOut) if (fs.has(p)) outputs[p] = dec.decode(fs.get(p))
        return { ...r, outputs, diagnostics: parseDiagnostics(r.stdout + '\n' + r.stderr, files, r.exitCode) }
    }

    return {
        requiredLibraries(files) {
            return requiredLibraryNames(files).filter(name => libs.find(l => l.name === name)?.path && !libMlibBytes.has(name))
        },
        addLibraryMlib(name, text) {
            const lib = libs.find(l => l.name === name && l.path)
            if (!lib) throw new Error(`unknown preinstalled library '${name}'`)
            lib.mlib = text
            libMlibBytes.set(name, enc.encode(text))
        },
        async check(files) {
            const r = await runMinic(files, ['--check'])
            return r.diagnostics
        },

        async compile(files, opts = {}) {
            const timings = {}
            const t0 = Date.now()
            const flags = ['--emit-ll', `${WORK}/out.ll`, '--target=wasm32']
            if (opts.emitMir) flags.push('--emit-mir', `${WORK}/out.mir`)
            const r = await runMinic(files, flags, [`${WORK}/out.ll`, `${WORK}/out.mir`])
            timings.minic = Date.now() - t0
            const result = { diagnostics: r.diagnostics, timings }
            if (opts.emitMir && r.outputs[`${WORK}/out.mir`] !== undefined) result.mir = r.outputs[`${WORK}/out.mir`]
            const ll = r.outputs[`${WORK}/out.ll`]
            if (opts.emitLlvmIr && ll !== undefined) result.llvmIr = ll
            if (r.exitCode !== 0 || ll === undefined) {
                timings.total = Date.now() - t0
                return result
            }
            if (!clang) throw new Error('the toolchain level "full" is not loaded')
            const level = opts.optLevel ?? '1'
            const t1 = Date.now()
            const compiled = await runClang(clang, ['--target=wasm32-wasip1', `-O${level}`, '-Wno-override-module', '-c', 'prog.ll', '-o', 'prog.o'], { 'prog.ll': ll })
            timings.clang = Date.now() - t1
            if (!compiled.ok) {
                result.diagnostics.push(internalError('clang failed on the generated LLVM IR', compiled.stderr, files))
                timings.total = Date.now() - t0
                return result
            }
            const t2 = Date.now()
            const linked = await runClang(clang, ['--target=wasm32-wasip1', 'prog.o', 'libminirt-wasm32.a', 'minirt-entry-wasm32.o', '-o', 'prog.wasm'],
                { 'prog.o': compiled.files['prog.o'], 'libminirt-wasm32.a': libminirt, 'minirt-entry-wasm32.o': entry })
            timings.link = Date.now() - t2
            if (!linked.ok) {
                result.diagnostics.push(internalError('linking failed', linked.stderr, files))
            } else {
                result.wasm = linked.files['prog.wasm']
            }
            timings.total = Date.now() - t0
            return result
        },
    }
}

async function runClang(clang, args, files) {
    const err = []
    const dec = new TextDecoder()
    const options = { stderr: b => { if (b) err.push(typeof b === 'string' ? b : dec.decode(b)) }, stdout: () => {}, decodeASCII: false }
    try {
        const tree = await clang(args, files, options)
        return { ok: true, files: tree ?? {}, stderr: err.join('') }
    } catch (e) {
        if (e && typeof e.code === 'number' && e.files !== undefined) {
            return e.code === 0 ? { ok: true, files: e.files, stderr: err.join('') } : { ok: false, files: e.files, stderr: err.join('') }
        }
        return { ok: false, files: {}, stderr: err.join('') + String(e && e.message || e) }
    }
}

function internalError(message, detail, files) {
    return { file: files[0]?.name ?? 'main.kt', line: 1, col: 1, endLine: 1, endCol: 2, severity: 'error', message: `${message}: ${detail.trim().split('\n').slice(0, 6).join(' | ')}` }
}

// ---- diagnostics: the text minic prints, as the Diagnostic objects of types.ts ----

const MINI_HEADER = /^(error|warning|info)\[(M\d{4})\]: (.*)$/
const MINI_SPAN = /^\s+--> (.+?):(\d+):(\d+)(?:\s{2,}(.*))?$/
const KOTLIN_DIAG = /^([^\s:][^:]*\.kt):(\d+):(\d+): (error|warning): (.*)$/
const HELP = /^\s+help: (.*)$/

export function parseDiagnostics(text, files, exitCode) {
    const nameOf = p => {
        const rel = p.startsWith(WORK + '/') ? p.slice(WORK.length + 1) : p
        const hit = files.find(f => safeBase(f.name) === rel || f.name === rel)
        return hit ? hit.name : rel
    }
    const textOf = p => (files.find(f => f.name === nameOf(p)) ?? { text: '' }).text
    const span = (file, line, col) => {
        const src = textOf(file).split('\n')[line - 1] ?? ''
        const m = /^[A-Za-z0-9_]+/.exec(src.slice(col - 1))
        return { file: nameOf(file), line, col, endLine: line, endCol: col + (m ? m[0].length : 1) }
    }
    const out = []
    let cur = null
    for (const raw of text.split('\n')) {
        const line = raw.replace(/^Exception in thread "main" [\w.]+: /, '')
        let m
        if ((m = MINI_HEADER.exec(line))) {
            cur = { code: m[2], severity: m[1] === 'info' ? 'note' : m[1], message: m[3], _spans: [], help: [] }
            out.push(cur)
        } else if ((m = KOTLIN_DIAG.exec(line))) {
            cur = { severity: m[4], message: m[5], _spans: [], help: [], _primary: span(m[1], +m[2], +m[3]) }
            out.push(cur)
        } else if ((m = /^error: (?!could not compile )(.+)$/.exec(line))) {
            // a general error without a position (internal compiler error, usage error, error limit)
            cur = { severity: 'error', message: m[1], _spans: [], help: [] }
            out.push(cur)
        } else if (cur) {
            let s
            if ((s = MINI_SPAN.exec(line))) cur._spans.push({ ...span(s[1], +s[2], +s[3]), label: s[4] ?? '' })
            else if ((s = HELP.exec(line))) cur.help.push(s[1])
            else if (line.trim() === '') cur = null
        }
    }
    const result = out.map(d => {
        const primary = d._primary ?? d._spans.shift() ?? { file: files[0]?.name ?? 'main.kt', line: 1, col: 1, endLine: 1, endCol: 2 }
        const { label: _label, ...span0 } = primary
        const diag = { ...span0, severity: d.severity, message: d.message }
        if (d.code) diag.code = d.code
        if (d._spans.length) diag.secondary = d._spans.map(s => ({ ...s, label: s.label }))
        if (d.help.length) diag.help = d.help.join('\n')
        return diag
    })
    if (exitCode !== 0 && !result.some(d => d.severity === 'error')) {
        const lines = text.split('\n').map(l => l.replace(/^Exception in thread "main" [\w.]+: /, '').trim()).filter(Boolean)
        const msg = lines.reverse().find(l => !l.startsWith('at ')) ?? 'the compiler failed'
        result.push({ file: files[0]?.name ?? 'main.kt', line: 1, col: 1, endLine: 1, endCol: 2, severity: 'error', message: msg })
    }
    return result
}

const safeBase = n => n.replace(/^\/+/, '')
