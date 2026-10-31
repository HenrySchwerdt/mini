#!/usr/bin/env node
// Compile the shipped digit experiment once at build time. Browser edits still compile locally.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { assembleProgram, newCell } from '../../website/.vitepress/theme/playground/notebook.ts'
import { datasetSource, datasetUrl, fetchDataset } from '../../website/.vitepress/theme/playground/datasets.ts'
import { CompileCache } from '../../website/.vitepress/theme/playground/compile-cache.ts'

const dir = resolve(process.argv[2] ?? 'build/site/toolchain')
const manifestPath = join(dir, 'manifest.json')
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'))
const source = readFileSync(new URL('../../website/.vitepress/theme/playground/digit-training.kt', import.meta.url), 'utf8')
const cells = source.split(/^\/\/ %% .+\r?\n/gm).slice(1).map(s => newCell('code', s.trim()))
const csv = await fetchDataset(datasetUrl(source), new AbortController().signal)
const files = [assembleProgram(cells, cells.length - 1).file, datasetSource(csv)]
const options = { optLevel: '1' }
const key = await new CompileCache().key(manifest.version, files, options)
if (manifest.programs?.some(p => p.key === key && existsSync(join(dir, p.path)))) {
  console.log('precompiled digit experiment is current')
  process.exit(0)
}

let id = 0
const pending = new Map()
globalThis.self = { postMessage(message) {
  if (message.type === 'progress') return
  const p = pending.get(message.id); pending.delete(message.id)
  if (message.type === 'error') p.reject(new Error(message.message))
  else p.resolve(message.value)
} }
const realFetch = globalThis.fetch
globalThis.fetch = async (url, init) => {
  if (!String(url).startsWith('file:')) return realFetch(url, init)
  return new Response(readFileSync(new URL(url)))
}
await import(pathToFileURL(join(dir, manifest.worker)).href)
const call = (type, body) => new Promise((resolve, reject) => {
  const next = ++id; pending.set(next, { resolve, reject })
  self.onmessage({ data: { id: next, type, ...body } })
})
await call('load', { level: 'full', manifestUrl: pathToFileURL(manifestPath).href })
const result = await call('compile', { files, opts: options })
if (!result.wasm || result.diagnostics.some(d => d.severity === 'error')) throw new Error(JSON.stringify(result.diagnostics))
const path = `${manifest.version}/examples/digits-${key.split('/').at(-1)}.wasm`
mkdirSync(join(dir, manifest.version, 'examples'), { recursive: true })
writeFileSync(join(dir, path), result.wasm)
manifest.programs = [{ key, path, bytes: result.wasm.length, diagnostics: result.diagnostics }]
writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n')
console.log(`precompiled digit experiment: ${result.wasm.length} bytes; ${JSON.stringify(result.timings)}`)
