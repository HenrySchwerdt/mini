#!/usr/bin/env node
// Runs every code cell of the docs through the in-browser toolchain (the real worker bundle and wasm files, in node) and
// compares the result with the expectation, like check-samples.sh does with the native compiler. A cell that passes here
// does what the Run / Check button of the page does.
//
//   node website/scripts/run-snippets-wasm.mjs <toolchain-dir> [filter] [--keep-going]
//
// <toolchain-dir> is website/public/toolchain (or the deployed one). The cells are the samples next to which the docs
// show a Run or Check button (NAME.out / NAME.check, without NAME.norun) and the programs of scripts/extract-snippets.mjs.
import { existsSync, mkdtempSync, openSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { spawnSync } from 'node:child_process'
import { WASI } from 'node:wasi'

const here = dirname(fileURLToPath(import.meta.url))
const site = resolve(here, '..')
const dir = resolve(process.argv[2] ?? join(site, 'public/toolchain'))
const filter = process.argv.slice(3).find((a) => !a.startsWith('--')) ?? ''
const keepGoing = process.argv.includes('--keep-going')
const manifestUrl = pathToFileURL(join(dir, 'manifest.json')).href
const manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf8'))

const pending = new Map()
globalThis.self = {
  postMessage(msg) {
    if (msg.type === 'progress') return
    const p = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.type === 'error') p.reject(new Error(msg.message))
    else p.resolve(msg.value)
  },
}
const realFetch = globalThis.fetch
globalThis.fetch = async (url, init) => {
  const u = String(url)
  if (!u.startsWith('file:')) return realFetch(url, init)
  const buf = readFileSync(new URL(u))
  return new Response(new ReadableStream({ start(c) { for (let i = 0; i < buf.length; i += 1 << 20) c.enqueue(buf.subarray(i, i + (1 << 20))); c.close() } }), { headers: { 'content-length': String(buf.length) } })
}
await import(pathToFileURL(join(dir, manifest.worker)).href)
let nextId = 1
const call = (type, body) => new Promise((resolve, reject) => {
  const id = nextId++
  pending.set(id, { resolve, reject })
  self.onmessage({ data: { id, type, ...body } })
})
await call('load', { level: 'full', manifestUrl })

async function runWasm(wasm) {
  const tmp = mkdtempSync(join(tmpdir(), 'mini-snip-'))
  const f = join(tmp, 'out.txt')
  const fd = openSync(f, 'w')
  const wasi = new WASI({ version: 'preview1', args: ['main'], returnOnExit: true, stdout: fd, stderr: fd })
  const { instance } = await WebAssembly.instantiate(wasm, wasi.getImportObject())
  const code = wasi.start(instance)
  const out = readFileSync(f, 'utf8')
  rmSync(tmp, { recursive: true, force: true })
  return { code, out }
}

const norm = (s) => s.replace(/\r\n/g, '\n').split('\n').map((l) => l.replace(/\s+$/, '')).join('\n').replace(/\n+$/, '')
const sidecar = (base, ext) => (existsSync(base + ext) ? readFileSync(base + ext, 'utf8') : undefined)

const snippets = join(mkdtempSync(join(tmpdir(), 'mini-extract-')), 's')
const ex = spawnSync('node', [join(here, 'extract-snippets.mjs'), snippets], { stdio: ['ignore', 'ignore', 'inherit'] })
if (ex.status !== 0) process.exit(2)

function* files(root) {
  for (const n of readdirSync(root).sort()) {
    const p = join(root, n)
    if (statSync(p).isDirectory()) yield* files(p)
    else if (n.endsWith('.kt')) yield p
  }
}

let pass = 0
let fail = 0
let skipped = 0
for (const kt of [...files(join(site, 'samples')), ...files(snippets)]) {
  const base = kt.slice(0, -3)
  const name = kt.startsWith(snippets) ? `page:${relative(snippets, kt)}` : relative(join(site, 'samples'), kt)
  if (filter && !name.includes(filter)) continue
  if (existsSync(base + '.norun')) { skipped++; continue }
  const out = sidecar(base, '.out')
  const run = sidecar(base, '.run')
  const check = sidecar(base, '.check')
  if (out === undefined && run === undefined && check === undefined) { skipped++; continue }
  const text = readFileSync(kt, 'utf8')
  const bad = (why) => { fail++; console.log(`FAIL  ${name}: ${why}`); if (!keepGoing) process.exit(1) }
  try {
    if (check !== undefined) {
      const [kind, code] = check.trim().split(/\s+/)
      const diags = await call('check', { files: [{ name: 'main.kt', text }] })
      const codes = [...new Set(diags.filter((d) => d.code).map((d) => d.code))]
      const errors = diags.some((d) => d.severity === 'error')
      const ok = kind === 'ok' ? codes.length === 0 && !errors : kind === 'error' ? errors && codes.length === 1 && codes[0] === code : !errors && codes.length === 1 && codes[0] === code
      if (ok) { pass++; console.log(`ok    ${name} (check ${check.trim()})`) } else bad(`expected ${check.trim()}, got ${JSON.stringify(diags.map((d) => `${d.code ?? d.severity}@${d.line}:${d.col} ${d.message}`))}`)
      continue
    }
    const r = await call('compile', { files: [{ name: 'main.kt', text }], opts: {} })
    if (!r.wasm) { bad(`does not compile: ${JSON.stringify(r.diagnostics.slice(0, 2).map((d) => `${d.line}:${d.col} ${d.message}`))}`); continue }
    const res = await runWasm(r.wasm)
    if (res.code !== (Number(run?.trim()) || 0)) bad(`exit status ${res.code}: ${res.out.slice(-200)}`)
    else if (out !== undefined && norm(res.out) !== norm(out)) bad(`output differs:\n--- expected\n${out}\n--- got\n${res.out}`)
    else { pass++; console.log(`ok    ${name} (run)`) }
  } catch (e) {
    bad(`exception: ${e.message}`)
  }
}
console.log(`\nwasm snippets: ${pass} passed, ${fail} failed, ${skipped} without a browser cell`)
process.exit(fail ? 1 : 0)
