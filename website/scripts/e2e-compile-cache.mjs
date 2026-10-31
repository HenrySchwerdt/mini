// Uses the real shipped experiment, then optionally changes source to exercise local compilation.
// Start docs:preview first. node scripts/e2e-compile-cache.mjs <base-url> [--compile-edit]
import assert from 'node:assert/strict'
import { chromium } from 'playwright'
const base = process.argv[2] ?? 'http://127.0.0.1:4173/mini/'
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
} finally { await browser.close() }
