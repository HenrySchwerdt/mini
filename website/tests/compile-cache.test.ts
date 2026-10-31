import { test } from 'node:test'
import assert from 'node:assert/strict'
import { CompileCache } from '../.vitepress/theme/playground/compile-cache.ts'
import { newCell, runAllCellIndices, assembleProgram } from '../.vitepress/theme/playground/notebook.ts'
import { readFileSync } from 'node:fs'
import type { CompileResult } from '../.vitepress/theme/playground/types.ts'

const wasm = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0])
const result = (): CompileResult => ({ wasm: wasm.slice(), diagnostics: [], timings: { total: 1000 } })
function storage() {
  const entries = new Map<string, Response>()
  const cache = {
    async match(key: string) { return entries.get(key)?.clone() },
    async put(key: string, response: Response) { entries.set(key, response.clone()) },
    async delete(key: string | Request) { return entries.delete(typeof key === 'string' ? key : key.url) },
    async keys() { return [...entries.keys()].map(k => new Request(k)) },
  }
  return { entries, api: { open: async () => cache } as unknown as CacheStorage }
}

test('compile keys include toolchain, source, filenames and compiler options', async () => {
  const cache = new CompileCache()
  const files = [{ name: 'main.kt', text: 'println(1)' }]
  const key = await cache.key('v1', files, {})
  assert.equal(key, await cache.key('v1', files, { optLevel: '1', emitMir: false }))
  for (const other of [
    cache.key('v2', files, {}), cache.key('v1', [{ name: 'other.kt', text: 'println(1)' }], {}),
    cache.key('v1', [{ name: 'main.kt', text: 'println(2)' }], {}),
    cache.key('v1', files, { optLevel: '0' }), cache.key('v1', files, { emitMir: true }),
  ]) assert.notEqual(key, await other)
})

test('memory and persistent hits return independent wasm buffers and preserve warnings', async () => {
  const disk = storage()
  const a = new CompileCache(disk.api)
  const compiled = result()
  compiled.diagnostics = [{ severity: 'warning', file: 'main.kt', line: 1, col: 1, endLine: 1, endCol: 2, message: 'test warning' }]
  await a.put('https://mini.invalid/a', compiled)
  compiled.wasm![0] = 42
  const memory = await a.get('https://mini.invalid/a')
  assert.equal(memory?.wasm?.[0], 0)
  memory!.wasm![0] = 99
  const b = new CompileCache(disk.api)
  const persisted = await b.get('https://mini.invalid/a')
  assert.deepEqual(persisted?.wasm, wasm)
  assert.equal(persisted?.diagnostics[0].message, 'test warning')
  assert.equal((await a.get('https://mini.invalid/a'))?.wasm?.[0], 0)
})

test('cache excludes failures, bounds entries, and ignores corrupt or unavailable storage', async () => {
  const disk = storage(); const cache = new CompileCache(disk.api)
  await cache.put('https://mini.invalid/fail', { diagnostics: [], timings: {} })
  assert.equal(await cache.get('https://mini.invalid/fail'), undefined)
  for (let i = 0; i < 10; i++) await cache.put(`https://mini.invalid/${i}`, result())
  assert.equal(disk.entries.size, 8)
  assert.equal(await cache.get('https://mini.invalid/0'), undefined)
  disk.entries.set('https://mini.invalid/corrupt', new Response('garbage'))
  assert.equal(await new CompileCache(disk.api).get('https://mini.invalid/corrupt'), undefined)
  const broken = new CompileCache({ open: async () => { throw new Error('quota') } } as unknown as CacheStorage)
  await broken.put('https://mini.invalid/a', result())
  assert.deepEqual((await broken.get('https://mini.invalid/a'))?.wasm, wasm)
})

test('Run all bundles helper definitions but preserves effects, mains, and final diagnostics', () => {
  const cells = [
    newCell('code', 'import mini.tensor.*\nfun square(n: Int) = n*n'),
    newCell('code', 'val start = compute()'),
    newCell('code', 'data class Point(val x: Int)\nfun next() = square(2)'),
    newCell('code', 'println(next())'),
    newCell('code', 'object Counter { init { println("init") } }'),
    newCell('code', 'fun main() { println("main") }'),
    newCell('code', 'fun finalHelper() = 1'),
  ]
  assert.deepEqual(runAllCellIndices(cells), [1, 3, 4, 5, 6])
  assert.match(assembleProgram(cells, 3).file.text, /fun square/)
  assert.deepEqual(runAllCellIndices([newCell('code', 'fun unfinished() = missing')]), [0])
})

test('Run all compiles the digit lab once while keeping all earlier declarations', () => {
  const raw = readFileSync(new URL('../.vitepress/theme/playground/digit-training.kt', import.meta.url), 'utf8')
  const cells = raw.split(/^\/\/ %% .+\r?\n/gm).slice(1).map(s => newCell('code', s.trim()))
  assert.deepEqual(runAllCellIndices(cells), [10])
  const assembled = assembleProgram(cells, 10).file.text
  assert.match(assembled, /fun trainMatrices/)
  assert.match(assembled, /fun trainLinear/)
})
