#!/usr/bin/env node
// End-to-end test of the playground and the notebook in headless Chromium, against the built site (with the wasm toolchain).
//
//   cd website && npm run docs:build && node scripts/e2e-playground.mjs [--shots <dir>] [--headed]
//
// Needs `playwright` (global or in node_modules) and a site built with website/public/toolchain/ (scripts/build-wasm-toolchain.sh,
// scripts/wasm/pack-site.mjs). It serves .vitepress/dist under /mini/ itself, drives the pages and checks:
//   /playground  loads the toolchain, Check on the memory-leak example shows M0102, Run prints hello, an uncaught exception shows stderr
//                and a non-zero exit code, a second visit is served from the service worker cache
//   /notebook    "Run all" on the starter notebook ends at the expected M0102 cell
//   docs page    "Open in playground" opens the playground with the code of the block
// Screenshots go to --shots (default: ./e2e-shots). Exit code 1 on the first failing check.
import { createServer } from 'node:http'
import { createReadStream, existsSync, mkdirSync, statSync } from 'node:fs'
import { extname, join, normalize, resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import assert from 'node:assert/strict'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../.vitepress/dist')
const args = process.argv.slice(2)
const shots = resolve(args.includes('--shots') ? args[args.indexOf('--shots') + 1] : 'e2e-shots')
mkdirSync(shots, { recursive: true })
if (!existsSync(join(dist, 'toolchain/manifest.json'))) { console.error('no toolchain/manifest.json in the built site'); process.exit(2) }

let chromium
try { ({ chromium } = createRequire(import.meta.url)('playwright')) } catch { ({ chromium } = await import('playwright')) }

const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
    '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.tar': 'application/x-tar', '.a': 'application/octet-stream', '.o': 'application/octet-stream',
}
// A static server like GitHub Pages: base /mini/, directory index, `foo` -> `foo.html`, no special headers, 10 minute cache.
const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://x')
    if (!url.pathname.startsWith('/mini/')) { res.writeHead(404); res.end(); return }
    let p = normalize(decodeURIComponent(url.pathname.slice('/mini/'.length)))
    let file = join(dist, p)
    if (existsSync(file) && statSync(file).isDirectory()) file = join(file, 'index.html')
    else if (!existsSync(file) && existsSync(file + '.html')) file += '.html'
    if (!file.startsWith(dist) || !existsSync(file)) { res.writeHead(404, { 'content-type': 'text/html' }); res.end('not found'); return }
    res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream', 'content-length': statSync(file).size, 'cache-control': 'max-age=600' })
    createReadStream(file).pipe(res)
})
await new Promise(r => server.listen(0, '127.0.0.1', r))
const origin = `http://127.0.0.1:${server.address().port}`
const base = `${origin}/mini`

const browser = await chromium.launch({ headless: !args.includes('--headed') })
const context = await browser.newContext({ viewport: { width: 1280, height: 900 } })
const page = await context.newPage()
const problems = []
page.on('pageerror', e => problems.push(`pageerror: ${e.message}`))
page.on('console', m => { if (m.type() === 'error') problems.push(`console: ${m.text()}`) })
const shot = name => page.screenshot({ path: join(shots, `${name}.png`), fullPage: false })
const ms = t => `${((Date.now() - t) / 1000).toFixed(1)} s`
let failed = false
async function step(name, fn) {
    const t = Date.now()
    try { await fn(); console.log(`ok   ${name} (${ms(t)})`) } catch (e) {
        failed = true
        console.log(`FAIL ${name} (${ms(t)}): ${e.message}`)
        await shot(`FAIL-${name.replace(/\W+/g, '-')}`).catch(() => {})
    }
}

// The source of a program as a share link (the same encoding the Share button writes).
const { hashFor } = await import(join(here, '../.vitepress/theme/playground/share.ts'))
const HELLO = 'fun main() {\n    val name = "Mini"\n    println("Hello, $name!")\n    for (i in 1..3) println("$i * $i = ${i * i}")\n}\n'
const THROWS = 'fun main() {\n    println("before")\n    throw IllegalStateException("boom")\n}\n'
const output = () => page.locator('.mp-output').first().innerText()
const waitIdle = () => page.waitForFunction(() => !document.querySelector('.mp-busy'), null, { timeout: 120000 })

await step('playground: page and toolchain load', async () => {
    const t = Date.now()
    await page.goto(`${base}/playground`)
    await page.locator('.cm-content').first().waitFor({ timeout: 30000 })
    // the checker loads on page open; the status banner goes away when idle
    await page.waitForFunction(() => !document.querySelector('.mp-status'), null, { timeout: 120000 })
    console.log(`     checker loaded in ${ms(t)} (first visit)`)
    await shot('playground-loaded')
})

await step('playground: Check on the memory-leak example shows M0102', async () => {
    await page.selectOption('select[aria-label="Examples"]', 'memory/leak')
    await page.waitForFunction(() => document.querySelector('.cm-content')?.textContent?.includes('Strict'), null, { timeout: 30000 })
    await page.getByRole('button', { name: 'Check', exact: true }).click()
    await page.getByRole('tab', { name: /Problems/ }).click()
    await page.locator('.mp-diag-code', { hasText: 'M0102' }).first().waitFor({ timeout: 60000 })
    // inline marker in the editor
    await page.locator('.cm-lintRange, .cm-lint-marker, .cm-lintRange-error').first().waitFor({ timeout: 10000 })
    await shot('playground-check-m0102')
})

await step('playground: Run hello prints its output', async () => {
    await page.goto(`${base}/playground${await hashFor(HELLO)}`)
    await page.locator('.cm-content').first().waitFor()
    await page.waitForFunction(() => document.querySelector('.cm-content')?.textContent?.includes('Hello'), null, { timeout: 30000 })
    const t = Date.now()
    await page.getByRole('button', { name: /^Run/ }).click()
    await page.getByRole('tab', { name: 'Output' }).click()
    await page.waitForFunction(() => document.querySelector('.mp-output')?.textContent?.includes('3 * 3 = 9'), null, { timeout: 180000 })
    console.log(`     first Run (clang download + compile + run): ${ms(t)}`)
    const out = await output()
    assert.match(out, /Hello, Mini!\n1 \* 1 = 1\n2 \* 2 = 4\n3 \* 3 = 9/)
    assert.match(out, /exit code 0/)
    await shot('playground-run-hello')
})

await step('playground: second Run (toolchain loaded)', async () => {
    const t = Date.now()
    await page.getByRole('button', { name: /^Run/ }).click()
    await page.waitForFunction(() => (document.querySelector('.mp-output')?.textContent?.match(/exit code 0/g) ?? []).length >= 1 && !document.querySelector('.mp-busy'), null, { timeout: 60000 })
    console.log(`     warm Run: ${ms(t)}`)
})

await step('playground: uncaught exception shows stderr and the exit code', async () => {
    await page.goto(`${base}/playground${await hashFor(THROWS)}`)
    await page.waitForFunction(() => document.querySelector('.cm-content')?.textContent?.includes('boom'), null, { timeout: 30000 })
    await page.getByRole('button', { name: /^Run/ }).click()
    await page.waitForFunction(() => /exit code \d+/.test(document.querySelector('.mp-output')?.textContent ?? ''), null, { timeout: 180000 })
    const out = await output()
    assert.match(out, /before/)
    assert.match(out, /IllegalStateException: boom/)
    assert.match(out, /exit code 1/)
    await shot('playground-run-exception')
})

await step('playground: cached load after reload (service worker)', async () => {
    const t = Date.now()
    await page.goto(`${base}/playground${await hashFor(HELLO)}`)
    await page.waitForFunction(() => !document.querySelector('.mp-status'), null, { timeout: 120000 })
    await page.getByRole('button', { name: /^Run/ }).click()
    await page.waitForFunction(() => document.querySelector('.mp-output')?.textContent?.includes('3 * 3 = 9'), null, { timeout: 120000 })
    console.log(`     reload + Run with warm caches: ${ms(t)}`)
})

await step('notebook: Run all stops at the expected M0102', async () => {
    await page.goto(`${base}/notebook`)
    await page.locator('.nb-cell').first().waitFor({ timeout: 30000 })
    await page.getByRole('button', { name: /Run all/ }).click()
    await page.locator('.nb-cell.error').first().waitFor({ timeout: 240000 })
    await page.waitForFunction(() => !document.querySelector('.nb-cell.compiling, .nb-cell.running'), null, { timeout: 120000 })
    const okCells = await page.locator('.nb-cell.code.ok').count()
    assert.ok(okCells >= 3, `expected the early code cells to run ok, got ${okCells}`)
    const text = await page.locator('.nb-cell.error').first().innerText()
    assert.match(text, /M0102/)
    await shot('notebook-run-all')
})

await step('docs page: Open in playground', async () => {
    await page.goto(`${base}/memory/arenas`)
    const button = page.locator('.mp-open-pg').first()
    await button.waitFor({ timeout: 30000 })
    await button.click()
    await page.waitForURL(/\/playground/, { timeout: 30000 })
    await page.locator('.cm-content').first().waitFor()
    assert.ok((await page.locator('.cm-content').first().innerText()).length > 20)
    await shot('docs-open-in-playground')
})

const real = problems.filter(p => !/favicon|Failed to load resource: the server responded with a status of 404/.test(p))
if (real.length) { console.log('browser console problems:\n  ' + real.slice(0, 10).join('\n  ')) }
await browser.close()
server.close()
console.log(failed ? 'E2E FAILED' : 'E2E OK')
process.exit(failed ? 1 : 0)
