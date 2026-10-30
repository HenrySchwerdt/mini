// Module worker of the Mini toolchain (protocol: website/.vitepress/theme/playground/types.ts, ToolchainRequest/Response).
// Bundled with esbuild into `mini-toolchain.worker.js` (scripts/wasm/pack-site.sh); the @yowasp/clang wasm files sit next to it.
//
//   load  level check : minic.wasm + stdlib.json (+ libs.json, the preinstalled libraries, when the toolchain has it)
//   load  level full  : + libminirt-wasm32.a, minirt-entry-wasm32.o and clang/wasm-ld (the @yowasp/clang modules, fetched by the package itself)
import { commands } from '@yowasp/clang'
import { createCore } from './toolchain-core.mjs'

let manifest = null
let baseUrl = null
let loaded = null // 'check' | 'full'
const assets = {}
let core = null

const post = (id, type, body) => self.postMessage({ id, type, ...body })

async function fetchBytes(url, label, stage, id, state) {
    const res = await fetch(url)
    if (!res.ok) throw new Error(`${label}: ${res.status} ${res.statusText}`)
    const total = Number(res.headers.get('content-length')) || (manifest.files.find(f => new URL(f.path, baseUrl).href === url)?.bytes ?? 0)
    const reader = res.body.getReader()
    const chunks = []
    let got = 0
    for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        chunks.push(value); got += value.length
        report(id, stage, label, got, total, state, value.length)
    }
    const out = new Uint8Array(got)
    let o = 0
    for (const c of chunks) { out.set(c, o); o += c.length }
    return out
}

function report(id, stage, label, loadedBytes, totalBytes, state, delta) {
    state.done += delta
    post(id, 'progress', { progress: { stage, label, loadedBytes, totalBytes, fraction: state.total ? Math.min(1, state.done / state.total) : undefined } })
}

async function load(id, level, manifestUrl) {
    if (!manifest) {
        baseUrl = manifestUrl
        manifest = await (await fetch(manifestUrl, { cache: 'no-cache' })).json()
    }
    const want = manifest.files.filter(f => level === 'full' || f.stage === 'check')
    const state = { total: want.reduce((n, f) => n + f.bytes, 0), done: 0 }
    const file = name => new URL(manifest.files.find(f => f.path === name || f.path.endsWith('/' + name)).path, baseUrl).href
    if (!assets.minic) {
        const bytes = await fetchBytes(file('minic.wasm'), 'Loading the compiler', 'minic.wasm', id, state)
        assets.minic = await WebAssembly.compile(bytes)
        const std = await fetchBytes(file('stdlib.json'), 'Loading the standard library', 'stdlib', id, state)
        assets.stdlib = JSON.parse(new TextDecoder().decode(std)).files
        // preinstalled libraries (mini.plot, ...): optional, absent from older toolchains
        assets.libs = []
        if (manifest.files.some(f => f.path.endsWith('/libs.json'))) {
            const libs = await fetchBytes(file('libs.json'), 'Loading the libraries', 'libs', id, state)
            assets.libs = JSON.parse(new TextDecoder().decode(libs)).libs
        }
    }
    if (level === 'full' && !assets.libminirt) {
        assets.libminirt = await fetchBytes(file('libminirt-wasm32.a'), 'Loading the Mini runtime', 'runtime', id, state)
        assets.entry = await fetchBytes(file('minirt-entry-wasm32.o'), 'Loading the Mini runtime', 'runtime', id, state)
        // clang + wasm-ld: the package downloads and compiles its own modules on the first command; run a no-op one to preload
        const clangBytes = manifest.files.filter(f => f.stage === 'full' && /llvm\./.test(f.path)).reduce((n, f) => n + f.bytes, 0)
        await commands.clang(['--version'], {}, {
            stdout: () => {}, stderr: () => {},
            fetchProgress: ({ totalLength, doneLength }) => {
                const t = totalLength || clangBytes
                post(id, 'progress', { progress: { stage: 'clang', label: 'Loading clang and wasm-ld', loadedBytes: doneLength, totalBytes: t,
                    fraction: state.total ? Math.min(1, (state.done + doneLength) / state.total) : undefined } })
            },
        }).catch(() => {})
    }
    loaded = level === 'full' ? 'full' : loaded ?? 'check'
    core = createCore({
        minic: assets.minic, stdlib: assets.stdlib, libs: assets.libs,
        clang: loaded === 'full' ? commands.clang : undefined, libminirt: assets.libminirt, entry: assets.entry,
    })
    return {
        version: manifest.version, loaded,
        capabilities: { check: true, compile: loaded === 'full', mir: true, llvmIr: true },
    }
}

self.onmessage = async ev => {
    const msg = ev.data
    try {
        if (msg.type === 'load') {
            post(msg.id, 'result', { value: await load(msg.id, msg.level, msg.manifestUrl) })
        } else if (msg.type === 'check') {
            if (!core) throw new Error('the toolchain is not loaded')
            post(msg.id, 'result', { value: await core.check(msg.files) })
        } else if (msg.type === 'compile') {
            if (!core) throw new Error('the toolchain is not loaded')
            const value = await core.compile(msg.files, msg.opts ?? {})
            self.postMessage({ id: msg.id, type: 'result', value }, value.wasm ? [value.wasm.buffer] : [])
        }
    } catch (e) {
        post(msg.id, 'error', { message: String(e && e.message || e) })
    }
}
