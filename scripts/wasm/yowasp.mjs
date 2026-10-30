#!/usr/bin/env node
// Runs one tool of the prebuilt YoWASP LLVM (clang, wasm-ld, ar, ...) under node on real files.
//
//   node yowasp.mjs [--mount <dir>]... [--out <dir>] <tool> <args...>
//
// The tools are WASI modules without access to the host file system, so the files they read are passed in as
// an in-memory tree (the same way the browser playground calls them). `--mount` loads a directory (relative
// to the current directory, recursively) into that tree under the same relative path; arguments must name
// files relative to the current directory. Files the tool creates (or changes) are written back to disk.
// Exit code = the tool's exit code. Needs `npm ci` in this directory (see package.json: pinned version).
import { readdirSync, readFileSync, writeFileSync, mkdirSync, statSync } from 'node:fs'
import { dirname, join, normalize } from 'node:path'

const argv = process.argv.slice(2)
const mounts = []
let outDir = '.'
while (argv[0] && argv[0].startsWith('--')) {
    const opt = argv.shift()
    if (opt === '--mount') mounts.push(argv.shift())
    else if (opt === '--out') outDir = argv.shift()
    else { console.error(`yowasp: unknown option ${opt}`); process.exit(2) }
}
const tool = argv.shift()
if (!tool) { console.error('usage: yowasp.mjs [--mount <dir>]... <tool> <args...>'); process.exit(2) }

const { commands } = await import('@yowasp/clang')
if (!commands[tool]) { console.error(`yowasp: no tool '${tool}' (have ${Object.keys(commands).join(', ')})`); process.exit(2) }

const tree = {}
const loaded = new Map() // path -> Uint8Array, to detect changes
function put(path, data) {
    const parts = normalize(path).split('/').filter(p => p && p !== '.')
    let node = tree
    for (const p of parts.slice(0, -1)) node = (node[p] ??= {})
    node[parts[parts.length - 1]] = data
    loaded.set(parts.join('/'), data)
}
function load(path) {
    const st = statSync(path)
    if (st.isDirectory()) for (const e of readdirSync(path)) load(join(path, e))
    else put(path, new Uint8Array(readFileSync(path)))
}
for (const m of mounts) load(m)
// arguments that name existing files are mounted too, so `clang -c a.c -o a.o` just works
for (const a of argv) {
    try { if (!a.startsWith('-') && statSync(a).isFile()) load(a) } catch { /* not a file */ }
}

const stdout = b => { if (b) process.stdout.write(b) }
const stderr = b => { if (b) process.stderr.write(b) }
let result
let code = 0
try {
    result = await commands[tool](argv, tree, { stdout, stderr, decodeASCII: false, fetchProgress: () => {} })
} catch (e) {
    if (e && typeof e.code === 'number' && e.files !== undefined) { code = e.code; result = e.files } else throw e
}
function walk(node, prefix, visit) {
    for (const [k, v] of Object.entries(node ?? {})) {
        const p = prefix ? `${prefix}/${k}` : k
        if (v instanceof Uint8Array || typeof v === 'string') visit(p, typeof v === 'string' ? new TextEncoder().encode(v) : v)
        else walk(v, p, visit)
    }
}
if (code === 0) walk(result, '', (p, data) => {
    const old = loaded.get(p)
    if (old && old.length === data.length && Buffer.compare(old, data) === 0) return
    const dest = join(outDir, p)
    mkdirSync(dirname(dest), { recursive: true })
    writeFileSync(dest, data)
})
process.exit(code)
