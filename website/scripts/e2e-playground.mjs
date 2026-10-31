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
//   preload      a fresh visitor on an ordinary docs page: the toolchain starts loading by itself (no click) and the badge goes to "ready"
//   docs pages   every runnable Kotlin fence has Run with the output below (a plain fence on /install, a sample on /tour/basics, the
//                hero example on the landing page); a tutorial page (/learn/*): Run, Edit + Run, Check with M0102, Run all, exercise goal
//   docs page    "Open in playground" opens the playground with the code of a block that is not a runnable cell
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

// ---- the cells of the documentation ----

await step('preload: the toolchain starts loading on a docs page without a click', async () => {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
    const p = await ctx.newPage()
    const urls = []
    ctx.on('request', r => urls.push(r.url()))
    await p.goto(`${base}/install`)
    await p.locator('.mc').first().waitFor({ timeout: 30000 })
    await p.locator('.tc-badge').waitFor({ timeout: 30000 })
    const t0 = Date.now()
    while (!urls.some(u => u.endsWith('minic.wasm')) && Date.now() - t0 < 30000) await p.waitForTimeout(100)
    assert.ok(urls.some(u => u.endsWith('minic.wasm')), 'minic.wasm was requested without any click')
    assert.equal(await p.locator('.cm-editor').count(), 0, 'the editor is not loaded before the first Edit')
    await p.screenshot({ path: join(shots, 'docs-preload-loading.png') })
    await p.locator('.tc-badge.ready').waitFor({ timeout: 240000 })
    assert.ok(!urls.some(u => u.endsWith('llvm.core.wasm')), 'clang remains unloaded until a cache miss needs compilation')
    // a plain fenced snippet (no sample file behind it): Run and the output right below it
    const cell = p.locator('.mc').first()
    await cell.getByRole('button', { name: 'Run', exact: true }).click()
    await cell.locator('.mp-output').waitFor({ timeout: 60000 })
    await p.waitForFunction(() => /exit code 0/.test(document.querySelector('.mc .mp-output')?.textContent ?? ''), null, { timeout: 60000 })
    const compileStart = Date.now()
    while (!urls.some(u => u.endsWith('llvm.core.wasm')) && Date.now() - compileStart < 240000) await p.waitForTimeout(100)
    assert.ok(urls.some(u => u.endsWith('llvm.core.wasm')), 'the compiler loads when a cache miss compiles this snippet')
    assert.match(await cell.locator('.mp-output').innerText(), /^Hello, Mini!\n/)
    assert.equal(await cell.locator('.mc-verdict.ok').count(), 1, 'the output matches the documented output')
    // the output panel is below the code
    const [codeBox, outBox] = await Promise.all([cell.locator('.mc-code').boundingBox(), cell.locator('.mp-output').boundingBox()])
    assert.ok(outBox.y > codeBox.y + codeBox.height - 1, 'output is below the code')
    await p.screenshot({ path: join(shots, 'docs-install-cell.png') })
    // the sample behind a <<< include on a tour page
    await p.goto(`${base}/tour/basics`)
    const tour = p.locator('.mc').first()
    await tour.getByRole('button', { name: 'Run', exact: true }).click()
    await p.waitForFunction(() => /liftoff/.test(document.querySelector('.mc .mp-output')?.textContent ?? ''), null, { timeout: 60000 })
    assert.equal(await tour.locator('.mc-verdict.ok').count(), 1)
    // the hero example on the landing page
    await p.goto(`${base}/`)
    const hero = p.locator('.hero-demo .mc')
    await hero.getByRole('button', { name: 'Run', exact: true }).click()
    await p.waitForFunction(() => /Hello from native code: 44/.test(document.querySelector('.hero-demo .mp-output')?.textContent ?? ''), null, { timeout: 60000 })
    await p.screenshot({ path: join(shots, 'home-hero-run.png') })
    await ctx.close()
})

await step('tutorial: Run, edit and run, Check, exercise goal, Run all', async () => {
    // reuse the service worker cache of the earlier steps
    await page.goto(`${base}/learn/hello`)
    const cells = page.locator('.mc')
    await cells.first().waitFor({ timeout: 30000 })
    // Run the first cell
    await cells.nth(0).getByRole('button', { name: 'Run', exact: true }).click()
    await cells.nth(0).locator('.mc-verdict.ok').waitFor({ timeout: 180000 })
    assert.match(await cells.nth(0).locator('.mp-output').innerText(), /Hello, Mini!/)
    // Learn opens visible code cells directly in the editor.
    const editor = cells.nth(0).locator('.cm-content')
    await editor.waitFor({ timeout: 30000 })
    await editor.fill('fun main() {\n    println("edited ${6 * 7}")\n}\n')
    await cells.nth(0).getByRole('button', { name: 'Run', exact: true }).click()
    await page.waitForFunction(() => /edited 42/.test(document.querySelector('.mc .mp-output')?.textContent ?? ''), null, { timeout: 60000 })
    assert.equal(await cells.nth(0).locator('.mc-verdict').count(), 0, 'an edited program is not compared with the documentation')
    await shot('tutorial-hello-edited')
    // Reset restores the original
    await cells.nth(0).getByRole('button', { name: 'Reset', exact: true }).click()
    await cells.nth(0).locator('.cm-editor').waitFor()
    assert.match(await cells.nth(0).locator('.cm-content').innerText(), /Hello, Mini!/)
    // notebook semantics: the third cell uses `name` from the cell above
    await cells.nth(2).getByRole('button', { name: 'Run', exact: true }).click()
    await page.waitForFunction(() => /Goodbye, Mini \(2027 is next year\)/.test(document.querySelectorAll('.mc')[2]?.textContent ?? ''), null, { timeout: 60000 })
    // an exercise: the starter does not reach the goal, the solution does
    const exercise = page.locator('.mc-exercise')
    await exercise.getByRole('button', { name: 'Run', exact: true }).click()
    await exercise.locator('.mp-output').waitFor({ timeout: 60000 })
    assert.equal(await exercise.locator('.mc-verdict.ok').count(), 0)
    assert.match(await exercise.locator('.mc-expected').innerText(), /Hello, Grace!/)
    await page.getByText('Show solution').click()
    const solution = page.locator('details .mc').first()
    await solution.getByRole('button', { name: 'Run', exact: true }).click()
    await solution.locator('.mc-verdict.ok').waitFor({ timeout: 60000 })

    // Check cells: the memory checker with the documented M0102
    await page.goto(`${base}/learn/memory-strict`)
    const leak = page.locator('.mc').first()
    await leak.getByRole('button', { name: 'Check', exact: true }).click()
    await leak.locator('.mc-diag').first().waitFor({ timeout: 120000 })
    const text = await leak.locator('.mc-out').innerText()
    assert.match(text, /M0102/)
    assert.match(text, /never released/)
    await leak.locator('.mc-verdict.ok').waitFor({ timeout: 5000 })
    await shot('tutorial-check-m0102')

    // Run all on a page: every cell passes
    await page.goto(`${base}/learn/basics`)
    await page.getByRole('button', { name: 'Run all' }).waitFor({ timeout: 30000 })
    await page.getByRole('button', { name: 'Run all' }).click()
    await page.waitForFunction(() => /^Run all$/.test(document.querySelector('.ra-btn.primary')?.textContent?.trim() ?? ''), null, { timeout: 240000 })
    const okCount = await page.locator('.mc-verdict.ok').count()
    const cellCount = await page.locator('.mc:not(.mc-exercise)').count()
    assert.ok(okCount >= cellCount - 1, `expected the cells of /learn/basics to match their documented output (${okCount} of ${cellCount})`)
    assert.equal(await page.locator('.ra-fail').count(), 0)
    await shot('tutorial-run-all')
})

await step('docs page: Open in playground (code block that is not a runnable cell)', async () => {
    await page.goto(`${base}/guide/c-interop`)
    const button = page.locator('.mp-open-pg').first()
    await button.waitFor({ timeout: 30000 })
    await button.click()
    await page.waitForURL(/\/playground/, { timeout: 30000 })
    await page.locator('.cm-content').first().waitFor()
    assert.ok((await page.locator('.cm-content').first().innerText()).length > 20)
    await shot('docs-open-in-playground')
})

await step('docs page: a cell opens in the playground with its program', async () => {
    await page.goto(`${base}/learn/hello`)
    await page.locator('.mc').first().getByRole('button', { name: 'Playground' }).click()
    await page.waitForURL(/\/playground/, { timeout: 30000 })
    await page.waitForFunction(() => document.querySelector('.cm-content')?.textContent?.includes('Hello, Mini!'), null, { timeout: 30000 })
})

await step('libraries: a mini.plot cell shows its figure below the cell (light and dark, desktop and mobile)', async () => {
    await page.goto(`${base}/libraries/plot`)
    await page.evaluate(() => localStorage.setItem('vitepress-theme-appearance', 'light'))
    await page.reload()
    const cell = page.locator('.mc', { hasText: 'Squares' }).first()
    await cell.getByRole('button', { name: 'Run', exact: true }).click()
    await cell.locator('.mp-svg > svg').waitFor({ timeout: 240000 })
    await page.waitForFunction(() => /exit code 0/.test(document.querySelector('.mc .mp-output')?.textContent ?? ''), null, { timeout: 60000 })
    assert.equal(await cell.locator('.mc-verdict.ok').count(), 1, 'the output matches the documented [display: image/svg+xml]')
    const [codeBox, figBox] = await Promise.all([cell.locator('.mc-code').boundingBox(), cell.locator('.mp-display').boundingBox()])
    assert.ok(figBox.y > codeBox.y + codeBox.height - 1, 'the figure is below the code')
    assert.ok(figBox.width > 200 && figBox.height > 100, 'the figure has a size')
    assert.equal(await cell.locator('.mp-display script').count(), 0)
    const fg = () => cell.locator('.mp-svg > svg').evaluate(e => getComputedStyle(e).color)
    const light = await fg()
    await cell.scrollIntoViewIfNeeded()
    await page.screenshot({ path: join(shots, 'plot-light-desktop.png') })
    await page.evaluate(() => document.documentElement.classList.add('dark'))
    const dark = await fg()
    assert.notEqual(light, dark, 'the figure text colour follows the theme')
    await cell.scrollIntoViewIfNeeded()
    await page.screenshot({ path: join(shots, 'plot-dark-desktop.png') })
    await page.setViewportSize({ width: 390, height: 800 })
    await cell.scrollIntoViewIfNeeded()
    const fig = await cell.locator('.mp-display').boundingBox()
    assert.ok(fig.x + fig.width <= 392, 'the figure fits the phone width')
    await page.screenshot({ path: join(shots, 'plot-dark-mobile.png') })
    await page.evaluate(() => document.documentElement.classList.remove('dark'))
    await page.screenshot({ path: join(shots, 'plot-light-mobile.png') })
    await page.setViewportSize({ width: 1280, height: 900 })
})

await step('libraries: HTML output is sandboxed, tables render', async () => {
    await page.goto(`${base}/libraries/display`)
    const cell = page.locator('.mc', { hasText: 'displayTable' }).first()
    await cell.getByRole('button', { name: 'Run', exact: true }).click()
    await cell.locator('.mp-table').waitFor({ timeout: 120000 })
    const frame = cell.locator('iframe.mp-html')
    assert.equal(await frame.getAttribute('sandbox'), '')
    await page.waitForTimeout(500)
    assert.equal(await page.evaluate(() => window.__xss === undefined), true)
    await cell.scrollIntoViewIfNeeded()
    await page.screenshot({ path: join(shots, 'display-table-html.png') })
})

const real = problems.filter(p => !/favicon|Failed to load resource: the server responded with a status of 404/.test(p))
if (real.length) { console.log('browser console problems:\n  ' + real.slice(0, 10).join('\n  ')) }
await browser.close()
server.close()
console.log(failed ? 'E2E FAILED' : 'E2E OK')
process.exit(failed ? 1 : 0)
