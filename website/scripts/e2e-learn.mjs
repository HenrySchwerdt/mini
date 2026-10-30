#!/usr/bin/env node
// UI regression checks for Learn and the shared sidebar. No wasm toolchain required.
// Run after `npm run docs:build`: npm run test:learn
import { createServer } from 'node:http'
import { createReadStream, existsSync, statSync, mkdirSync } from 'node:fs'
import { extname, resolve, sep } from 'node:path'
import assert from 'node:assert/strict'
import { chromium } from 'playwright'
import { learnChapters } from '../.vitepress/theme/learn-course.ts'

const dist = resolve(import.meta.dirname, '../.vitepress/dist')
const shots = process.env.MINI_LEARN_SHOTS
if (shots) mkdirSync(shots, { recursive: true })
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.wasm': 'application/wasm' }
const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
  if (!path.startsWith('/mini/')) { res.writeHead(404).end(); return }
  let file = resolve(dist, path.slice('/mini/'.length))
  if (file !== dist && !file.startsWith(dist + sep)) { res.writeHead(403).end(); return }
  if (existsSync(file) && statSync(file).isDirectory()) file = resolve(file, 'index.html')
  else if (!existsSync(file)) file += '.html'
  if (!existsSync(file)) { res.writeHead(404).end(); return }
  res.writeHead(200, { 'content-type': mime[extname(file)] ?? 'application/octet-stream' })
  createReadStream(file).pipe(res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const browser = await chromium.launch()
try {
  const page = await browser.newPage({ viewport: { width: 1720, height: 1050 } })
  const errors = []
  page.on('pageerror', (e) => errors.push(e.message))
  const base = `http://127.0.0.1:${server.address().port}/mini/`
  for (const width of [1024, 1440, 2048]) {
    await page.setViewportSize({ width, height: 1050 })
    for (const route of ['install', 'guide/getting-started', 'memory/strict', 'reference/mini', 'tour/basics', 'internals/', 'learn/hello']) {
      await page.goto(base + route)
      const box = await page.locator('.VPSidebar').boundingBox()
      assert.equal(box.x, 0, `${route}: sidebar touches viewport edge`)
      assert.ok(box.width >= 250 && box.width <= 300, `${route}: sidebar stays compact at ${width}px`)
      const padding = await page.locator('.VPSidebar').evaluate((el) => parseFloat(getComputedStyle(el).paddingLeft))
      assert.ok(padding <= 32, `${route}: no growing inner left gutter`)
      const overlap = await page.locator('.VPSidebar nav').evaluate((el) => el.getBoundingClientRect().top)
      assert.ok(overlap >= 64, `${route}: sidebar clears the header`)
    }
  }
  console.log('ok shared sidebar: seven page groups at three desktop widths')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(base + 'learn/hello')
  const first = page.locator('.mc').first()
  const editor = first.locator('.cm-content')
  await editor.waitFor()
  assert.equal(await page.locator('.nb-actions, .nb-md-edit, .nb-md[tabindex]').count(), 0, 'lesson prose and structure have no editing controls')
  assert.equal(await page.locator('.VPSidebar a[href*="/learn/"]').count(), learnChapters.length + 1, 'introduction and all chapters are reachable')
  await editor.fill('fun main() { println("edited lesson") }')
  assert.match(await editor.innerText(), /edited lesson/)
  await first.getByRole('button', { name: 'Reset', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('.mc .cm-content')?.textContent?.includes('Hello, Mini!'))
  assert.equal(await first.locator('.cm-editor').count(), 1, 'reset keeps the code editable')
  if (shots) await page.screenshot({ path: resolve(shots, 'learn-desktop.png') })
  await page.locator('.VPSidebar a[href$="/learn/generics"]').click()
  await page.locator('h1').filter({ hasText: 'Generics and type contracts' }).waitFor()
  await page.locator('.learn-pagination a').last().click()
  await page.locator('h1').filter({ hasText: 'Extensions, receivers and scope' }).waitFor()
  console.log('ok learn: direct editing, reset, fixed prose, chapter and next navigation')
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(base + 'learn/hello')
  await page.locator('.mc .cm-editor').first().waitFor()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth), 390, 'no mobile horizontal overflow')
  await page.getByRole('button', { name: 'Menu', exact: true }).click()
  await page.locator('.VPSidebar.open').waitFor()
  await page.locator('.VPSidebar a[href$="/learn/generics"]').click()
  await page.locator('h1').filter({ hasText: 'Generics and type contracts' }).waitFor()
  assert.equal(await page.locator('.VPSidebar.open').count(), 0, 'drawer closes after navigation')
  if (shots) await page.screenshot({ path: resolve(shots, 'learn-mobile.png') })
  await page.evaluate(() => document.documentElement.classList.add('dark'))
  await page.locator('.mc').first().scrollIntoViewIfNeeded()
  await page.locator('.mc .cm-editor').first().waitFor()
  if (shots) await page.screenshot({ path: resolve(shots, 'learn-mobile-dark.png') })
  console.log('ok mobile: no overflow, chapter drawer, navigation and dark mode')
  assert.deepEqual(errors, [], 'no browser errors')
} finally {
  await browser.close()
  await new Promise((r) => server.close(r))
}
