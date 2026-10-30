// Real browser/toolchain integration. Start docs:dev first, then run:
// node scripts/e2e-digits.mjs [http://127.0.0.1:5173/mini/]
import assert from 'node:assert/strict'
import { chromium } from 'playwright'
const base = process.argv[2] ?? 'http://127.0.0.1:5173/mini/'
const browser = await chromium.launch()
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const errors = []
  page.on('pageerror', e => errors.push(e.message))
  page.on('console', m => { if (m.type() === 'error') console.log('browser:', m.text()) })
  await page.goto(base + 'libraries/digits')
  await page.getByRole('button', { name: 'Run experiment', exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('button.primary')?.disabled, null, { timeout: 30000 })
  await page.getByRole('button', { name: 'Run experiment', exact: true }).click()
  await page.waitForFunction(() => {
    const cell = [...document.querySelectorAll('.nb-cell.code')].at(-1)
    return cell?.classList.contains('ok') || cell?.classList.contains('error')
  }, null, { timeout: 240000 })
  const output = await page.locator('.nb-cell.code').last().innerText()
  console.log(output.slice(-700))
  assert.equal(await page.locator('.nb-cell.code.error').count(), 0, output)
  assert.match(output, /Same weights after training: true/)
  assert.match(output, /Same bias after training: true/)
  const accuracies = [...output.matchAll(/held-out accuracy: ([\d.]+)%/g)].map(m => Number(m[1]))
  assert.equal(accuracies.length, 2)
  assert.ok(accuracies.every(a => a > 90), `Held-out accuracies: ${accuracies}`)
  assert.equal(accuracies[0], accuracies[1])
  const accuracy = accuracies[0]
  assert.equal(await page.locator('.mp-display[data-mime="image/svg+xml"]').count(), 3)
  assert.equal(await page.locator('.nb-cell.code').count(), 11)
  for (const caption of ['1 · pyplot', '2 · Axes', '3 · DSL']) assert.ok(output.includes(caption))
  const canvases = page.getByLabel('Draw a digit from 0 to 9 with your mouse or touch')
  assert.equal(await canvases.count(), 2)
  for (let i = 0; i < 2; i++) {
    const canvas = canvases.nth(i)
    await canvas.scrollIntoViewIfNeeded()
    const box = await canvas.boundingBox()
    await page.mouse.move(box.x + box.width * .5, box.y + box.height * .15)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * .5, box.y + box.height * .85, { steps: 20 })
    await page.mouse.up()
    assert.equal(await page.locator('.digit-answer').nth(i).innerText(), '1')
    if (i === 0) assert.equal(await page.locator('.digit-answer').nth(1).innerText(), '—', 'Pads are independent')
  }
  await page.screenshot({ path: '/tmp/mini-digit-lab.png' })
  await page.getByRole('button', { name: 'Clear drawing' }).nth(1).click()
  assert.equal(await page.locator('.digit-answer').nth(1).innerText(), '—')
  assert.equal(await page.locator('.digit-answer').nth(0).innerText(), '1')
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile page has no horizontal overflow')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: '/tmp/mini-digit-lab-mobile.png' })
  console.log(`ok: HTTP download, Mini training (${accuracy}%), SVG charts, drawing inference, clear and mobile layout`)
} finally { await browser.close() }
