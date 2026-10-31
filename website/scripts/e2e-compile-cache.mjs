// Uses the real shipped experiment, then optionally changes source to exercise local compilation.
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
  const run = async name => {
    const button = page.getByRole('button', { name, exact: true })
    await button.waitFor()
    await page.waitForFunction(name => ![...document.querySelectorAll('button')].find(b => b.textContent.trim() === name)?.disabled, name)
    const started = performance.now()
    await button.click()
    await page.waitForFunction(name => [...document.querySelectorAll('button')].find(b => b.textContent.trim() === name)?.disabled, name)
    await page.waitForFunction(name => ![...document.querySelectorAll('button')].find(b => b.textContent.trim() === name)?.disabled, name, { timeout: 240000 })
    await page.waitForFunction(() => {
      const c = [...document.querySelectorAll('.nb-cell.code')].at(-1)
      return c?.classList.contains('ok') || c?.classList.contains('error')
    }, null, { timeout: 240000 })
    assert.equal(await page.locator('.nb-cell.code.error').count(), 0, await page.locator('.nb-cell.code').last().innerText())
    assert.match(await page.locator('.nb-cell.code').last().innerText(), /Same weights after training: true/)
    const elapsed = performance.now() - started
    console.log(`${name}: ${Math.round(elapsed)} ms; ${JSON.stringify(await page.evaluate(() => window.compileStats))}`)
    return elapsed
  }
  await page.goto(base + 'libraries/digits')
  const cold = await run('Run experiment')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 0, fullLoad: 0 }, 'Shipped example starts without clang')
  const warm = await run('Run all')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 0, fullLoad: 0 }, 'Run all groups helpers and uses the cache')
  assert.equal(await page.locator('.nb-out').filter({ hasText: 'Definitions included in the next program.' }).count(), 10)
  await page.route('**/examples/*.wasm', route => route.abort())
  await page.reload()
  const reload = await run('Run experiment')
  assert.deepEqual(await page.evaluate(() => window.compileStats), { compile: 0, fullLoad: 0 }, 'Persistent cache works without the prebuilt asset after reload')
  assert.ok(cold < 10000 && warm < 10000 && reload < 10000, 'Default runs should complete within 10 seconds on this machine')
  if (process.argv.includes('--compile-edit')) {
    const source = page.locator('.nb-cell.code').last().locator('.cm-content')
    await source.click()
    await page.keyboard.press('ControlOrMeta+End')
    await page.keyboard.insertText('\nprintln("cache edit")\n')
    await page.waitForFunction(() => [...document.querySelectorAll('.nb-cell.code .cm-content')].at(-1)?.textContent?.includes('println("cache edit")'))
    await run('Run experiment')
    assert.equal(await page.evaluate(() => window.compileStats.compile), 1)
    await run('Run experiment')
    assert.equal(await page.evaluate(() => window.compileStats.compile), 1, 'Second edited run reuses local compilation')
  }
  assert.deepEqual(errors, [])
} finally {
  await browser.close()
  if (server?.listening) await new Promise(resolve => server.close(resolve))
}
