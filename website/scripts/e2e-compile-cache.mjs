// Exercises the real browser compiler and its on-device compile cache.
// Serves .vitepress/dist itself, or use: node scripts/e2e-compile-cache.mjs <base-url> [--compile-edit]
import assert from 'node:assert/strict'
import { chromium } from 'playwright'
import { createServer } from 'node:http'
import { createReadStream, existsSync, statSync } from 'node:fs'
import { extname, join, normalize, resolve, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const dist = resolve(here, '../.vitepress/dist')
const baseArg = process.argv[2]?.startsWith('http') ? process.argv[2] : undefined
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.wasm': 'application/wasm', '.svg': 'image/svg+xml', '.tar': 'application/x-tar', '.a': 'application/octet-stream', '.o': 'application/octet-stream' }
const server = baseArg ? undefined : createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost')
  if (!url.pathname.startsWith('/mini/')) { res.writeHead(404); res.end(); return }
  const relative = normalize(decodeURIComponent(url.pathname.slice('/mini/'.length)))
  let path = join(dist, relative)
  if (existsSync(path) && statSync(path).isDirectory()) path = join(path, 'index.html')
  else if (!existsSync(path) && existsSync(path + '.html')) path += '.html'
  if ((path !== dist && !path.startsWith(dist + sep)) || !existsSync(path)) { res.writeHead(404); res.end('not found'); return }
  res.writeHead(200, { 'content-type': mime[extname(path)] ?? 'application/octet-stream', 'content-length': statSync(path).size })
  createReadStream(path).pipe(res)
})
let base = baseArg
if (server) {
  if (!existsSync(join(dist, 'toolchain/manifest.json'))) throw new Error('Build the site with its wasm toolchain before running this test.')
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  base = `http://127.0.0.1:${server.address().port}/mini/`
}
base ??= 'http://127.0.0.1:4173/mini/'
const browser = await chromium.launch()
try {
  const page = await browser.newPage()
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  await page.addInitScript(() => {
    window.compileStats = { compile: 0, fullLoad: 0 }
    const post = Worker.prototype.postMessage
    Worker.prototype.postMessage = function(m, ...rest) {
      if (m?.type === 'compile') window.compileStats.compile++
      if (m?.type === 'load' && m.level === 'full') window.compileStats.fullLoad++
      return post.call(this, m, ...rest)
    }
  })
  await page.goto(base)
  const manifest = await (await fetch(new URL('toolchain/manifest.json', base))).json()
  assert.equal(manifest.programs, undefined, 'the toolchain ships no compiled program examples')

  const runSnippet = async (label) => {
    const cell = page.locator('.mc').first()
    const button = cell.getByRole('button', { name: 'Run', exact: true })
    await button.waitFor()
    await page.waitForFunction(() => !document.querySelector('.mc:first-of-type .mc-btn.primary')?.disabled)
    const started = performance.now()
    await button.click()
    await page.waitForFunction(() => document.querySelector('.mc:first-of-type .mc-out .mc-verdict') || document.querySelector('.mc:first-of-type .mc-out .mc-note'), null, { timeout: 240000 })
    const output = await cell.locator('.mc-out').innerText()
    assert.match(output, /Hello from native code: 44/)
    assert.match(output, /Matches the documented output/)
    const elapsed = performance.now() - started
    console.log(`${label}: ${Math.round(elapsed)} ms; ${JSON.stringify(await page.evaluate(() => window.compileStats))}`)
    return elapsed
  }

  const cold = await runSnippet('cold compile + run')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 1, fullLoad: 1 }, 'first default run compiles on-device')
  await runSnippet('warm same source')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 1, fullLoad: 1 }, 'same source reuses the on-device memory cache')

  await page.reload()
  await runSnippet('reload from Cache Storage')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 0, fullLoad: 0 }, 'successful compile survives reload in Cache Storage')

  if (process.argv.includes('--compile-edit')) {
    const cell = page.locator('.mc').first()
    await cell.getByRole('button', { name: 'Edit', exact: true }).click()
    const source = cell.locator('.mc-editor .cm-content')
    await source.click()
    await page.keyboard.press('ControlOrMeta+End')
    await page.keyboard.insertText('\n// cache edit\n')
    await page.waitForFunction(() => document.querySelector('.mc:first-of-type .cm-content')?.textContent?.includes('// cache edit'))
    await cell.getByRole('button', { name: 'Run', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.mc:first-of-type .mc-out .mc-verdict') || document.querySelector('.mc:first-of-type .mc-out .mc-note'), null, { timeout: 240000 })
    assert.equal(await page.evaluate(() => window.compileStats.compile), 1, 'editing changes the source key and compiles locally')
    assert.equal(await page.evaluate(() => window.compileStats.fullLoad), 1)
    await cell.getByRole('button', { name: 'Run', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.mc:first-of-type .mc-out .mc-verdict') || document.querySelector('.mc:first-of-type .mc-out .mc-note'), null, { timeout: 240000 })
    assert.equal(await page.evaluate(() => window.compileStats.compile), 1, 'second edited-source run uses the cache')
  }
  assert.ok(cold > 0)
  assert.deepEqual(errors, [])
} finally {
  await browser.close()
  if (server?.listening) await new Promise(resolve => server.close(resolve))
}
