#!/usr/bin/env node
// Runs the packed worker (pack-site.mjs output) in node with the site's message protocol and checks the answers:
//   node scripts/wasm/test-worker.mjs <site-toolchain-dir>      e.g. build/site/toolchain
// `self` and `fetch(file:)` are faked; the code under test is the real esbuild bundle and the real wasm files.
import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { WASI } from 'node:wasi'
import assert from 'node:assert/strict'

const dir = resolve(process.argv[2] ?? 'build/site/toolchain')
const manifestUrl = pathToFileURL(join(dir, 'manifest.json')).href
const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'))

const pending = new Map()
const events = []
globalThis.self = {
    postMessage(msg) {
        if (msg.type === 'progress') { events.push(msg.progress); return }
        const p = pending.get(msg.id)
        pending.delete(msg.id)
        if (msg.type === 'error') p.reject(new Error(msg.message)); else p.resolve(msg.value)
    },
}
const realFetch = globalThis.fetch
globalThis.fetch = async (url, init) => {
    const u = String(url)
    if (!u.startsWith('file:')) return realFetch(url, init)
    const buf = readFileSync(new URL(u))
    return new Response(new ReadableStream({ start(c) { for (let i = 0; i < buf.length; i += 1 << 20) c.enqueue(buf.subarray(i, i + (1 << 20))); c.close() } }),
        { headers: { 'content-length': String(buf.length) } })
}
await import(pathToFileURL(join(dir, manifest.worker)).href)

let nextId = 1
const call = (type, body) => new Promise((resolve, reject) => {
    const id = nextId++
    pending.set(id, { resolve, reject })
    self.onmessage({ data: { id, type, ...body } })
})
const run = async wasm => {
    let out = ''
    const { mkdtempSync, openSync, readFileSync: rf, writeFileSync } = await import('node:fs')
    const tmp = mkdtempSync('/tmp/mini-run-')
    const f = join(tmp, 'o.txt')
    const fd = openSync(f, 'w')
    const wasi = new WASI({ version: 'preview1', args: ['prog'], returnOnExit: true, stdout: fd, stderr: fd })
    const { instance } = await WebAssembly.instantiate(wasm, wasi.getImportObject())
    const code = wasi.start(instance)
    out = rf(f, 'utf8')
    void writeFileSync
    return { code, out }
}

const t0 = Date.now()
const info = await call('load', { level: 'check', manifestUrl })
assert.equal(info.loaded, 'check'); assert.equal(info.capabilities.compile, false)
assert.ok(events.some(e => e.stage === 'minic.wasm'), 'progress for minic.wasm')
console.log(`load check: ${Date.now() - t0} ms, ${events.length} progress events`)

const leak = `@file:MemoryMode(Mode.Strict)
import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    b.free()
    println(b.v)
}

fun main() {
    context(Heap()) { run() }
}
`
let t = Date.now()
let diags = await call('check', { files: [{ name: 'main.kt', text: leak }] })
console.log(`check (strict program): ${Date.now() - t} ms`, JSON.stringify(diags))
assert.equal(diags.length, 1); assert.equal(diags[0].code, 'M0101'); assert.equal(diags[0].severity, 'error')
assert.equal(diags[0].file, 'main.kt'); assert.equal(diags[0].line, 10); assert.ok(diags[0].endCol > diags[0].col)
assert.ok(diags[0].help)

diags = await call('check', { files: [{ name: 'main.kt', text: 'fun main() {\n    val x: Int = "a"\n    println(y)\n}\n' }] })
console.log('check (type errors):', JSON.stringify(diags))
assert.equal(diags.length, 2); assert.equal(diags[0].line, 2); assert.equal(diags[1].line, 3); assert.equal(diags[0].code, undefined)

diags = await call('check', { files: [{ name: 'main.kt', text: 'fun main() { println("hi") }\n' }] })
assert.deepEqual(diags, [])
diags = await call('check', { files: [{ name: 'main.kt', text: 'import mini.plot.*\nfun main() { plt.plot(listOf(0.0), listOf(1.0)) }\n' }] })
assert.deepEqual(diags, [], 'plot .mlib loads lazily on first import')
assert.ok(!events.some(e => e.stage === 'library-object'), 'check loads the plot .mlib without downloading its compile-only object')

await assert.rejects(() => call('compile', { files: [{ name: 'main.kt', text: 'fun main() {}' }], opts: {} }), /./)

t = Date.now()
const full = await call('load', { level: 'full', manifestUrl })
assert.equal(full.loaded, 'full'); assert.equal(full.capabilities.compile, true)
console.log(`load full: ${Date.now() - t} ms, last progress`, JSON.stringify(events[events.length - 1]))

const hello = 'fun main() {\n    val name = "Mini"\n    println("Hello, $name!")\n    for (i in 1..3) println("$i * $i = ${i * i}")\n}\n'
const r = await call('compile', { files: [{ name: 'main.kt', text: hello }], opts: { emitMir: true, emitLlvmIr: true } })
console.log('compile hello:', JSON.stringify(r.timings), 'wasm', r.wasm?.length, 'diags', r.diagnostics.length)
assert.deepEqual(r.diagnostics, [])
assert.ok(r.wasm instanceof Uint8Array && r.wasm.length > 1000)
assert.ok(r.llvmIr.includes('target triple = "wasm32'), 'LLVM IR returned'); assert.ok(r.mir.length > 100, 'MIR returned')
const ran = await run(r.wasm)
assert.equal(ran.code, 0)
assert.equal(ran.out, 'Hello, Mini!\n1 * 1 = 1\n2 * 2 = 4\n3 * 3 = 9\n')

const tensor = await call('compile', { files: [{ name: 'main.kt', text: 'import mini.tensor.*\nfun main() { val a = tensor(1.0, 2.0, 3.0); println((a * a).sum().item()) }\n' }], opts: { emitLlvmIr: true } })
assert.deepEqual(tensor.diagnostics, [])
assert.ok(events.some(e => e.stage === 'library-object'), 'compile loads the selected library object after its .mlib was already available')
assert.ok(tensor.wasm instanceof Uint8Array)
const tensorRan = await run(tensor.wasm)
assert.equal(tensorRan.code, 0)
assert.equal(tensorRan.out, '14.0\n')
assert.ok(tensor.llvmIr.length < 250_000, 'tensor LLVM IR contains only the user module when the cached library object is used')

const tensorModelSource = `import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.Adam
fun main() {
    manualSeed(7)
    val model = Sequential(Linear(2, 3), Tanh(), Linear(3, 1))
    println("model ${'$'}{model(tensor(doubleArrayOf(0.0, 1.0)))}")
    val w = tensor(5.0, -3.0, requiresGrad = true)
    val opt = Adam(listOf(w), lr = 0.2)
    opt.zeroGrad()
    (w * w).sum().backward()
    opt.step()
    println("weight ${'$'}w")
}
`
const tensorModel = await call('compile', { files: [{ name: 'main.kt', text: tensorModelSource }], opts: { emitLlvmIr: true } })
assert.deepEqual(tensorModel.diagnostics, [])
assert.ok(tensorModel.llvmIr.length < 250_000, 'NN and optimizer classes are imported from the precompiled tensor object')
const tensorModelRan = await run(tensorModel.wasm)
assert.equal(tensorModelRan.code, 0)
assert.ok(tensorModelRan.out.startsWith('model tensor('))
assert.ok(tensorModelRan.out.includes('weight tensor('))
const tensorModelFallback = await call('compile', { files: [{ name: 'main.kt', text: tensorModelSource }], opts: { optLevel: '0', emitLlvmIr: true } })
assert.deepEqual(tensorModelFallback.diagnostics, [])
assert.ok(tensorModelFallback.llvmIr.length > tensorModel.llvmIr.length, 'O0 compiles the library bodies instead of using O1 objects')
const tensorModelFallbackRan = await run(tensorModelFallback.wasm)
assert.equal(tensorModelFallbackRan.code, 0)
assert.equal(tensorModelFallbackRan.out, tensorModelRan.out, 'O1 library objects preserve model/optimizer output byte-for-byte')

const plotSource = label => `import mini.plot.*
fun main() {
    plt.figure(figsize = 6.0 to 3.5)
    plt.plot(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 4.0), label = "${label}")
    println(plt.toSvg())
}
`
const plot = await call('compile', { files: [{ name: 'plot.kt', text: plotSource('first') }], opts: { emitLlvmIr: true } })
assert.deepEqual(plot.diagnostics, [])
assert.ok(plot.llvmIr.length < 250_000, 'plot LLVM IR contains only the user module when the cached library object is used')
const plotRan = await run(plot.wasm)
assert.equal(plotRan.code, 0)
assert.ok(plotRan.out.includes('<svg'))
assert.ok(plotRan.out.includes('first'))

// An edited second cell must compile against the same cached library object and reflect the new source.
const editedPlot = await call('compile', { files: [{ name: 'plot.kt', text: plotSource('edited') }], opts: {} })
assert.deepEqual(editedPlot.diagnostics, [])
const editedPlotRan = await run(editedPlot.wasm)
assert.equal(editedPlotRan.code, 0)
assert.ok(editedPlotRan.out.includes('edited'))
assert.ok(!editedPlotRan.out.includes('first'))

const mixed = await call('compile', { files: [{ name: 'main.kt', text: `import mini.plot.*
import mini.tensor.*
fun main() {
    val x = tensor(2.0, requiresGrad = true)
    val y = x * x + x
    y.backward()
    println("grad ${'$'}{x.grad!!.item()}")
    plt.figure(figsize = 6.0 to 3.5)
    plt.plot(doubleArrayOf(0.0, 1.0, 2.0), doubleArrayOf(0.0, 1.0, 4.0), label = "mixed")
    println(plt.toSvg())
}
` }], opts: {} })
assert.deepEqual(mixed.diagnostics, [])
const mixedRan = await run(mixed.wasm)
assert.equal(mixedRan.code, 0)
assert.ok(mixedRan.out.startsWith('grad 5.0\n'))
assert.ok(mixedRan.out.includes('mixed'))

// Non-O1 options use the monolithic fallback until matching library objects are packaged.
const fallback = await call('compile', { files: [{ name: 'main.kt', text: 'import mini.tensor.*\nfun main() { val a = tensor(1.0, 2.0, 3.0); println((a * a).sum().item()) }\n' }], opts: { optLevel: '0', emitLlvmIr: true } })
assert.deepEqual(fallback.diagnostics, [])
assert.ok(fallback.llvmIr.length > tensor.llvmIr.length, 'O0 uses full library LLVM IR when O1 objects are incompatible')
const fallbackRan = await run(fallback.wasm)
assert.equal(fallbackRan.code, 0)
assert.equal(fallbackRan.out, '14.0\n')

const bad = await call('compile', { files: [{ name: 'main.kt', text: 'fun main() { val x: Int = "a" }' }], opts: {} })
assert.equal(bad.wasm, undefined); assert.equal(bad.diagnostics.length, 1)

const withMem = await call('compile', { files: [{ name: 'main.kt', text: leak }], opts: {} })
assert.equal(withMem.wasm, undefined); assert.equal(withMem.diagnostics[0].code, 'M0101')
console.log('worker protocol OK')
