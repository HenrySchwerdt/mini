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
  await page.getByRole('button', { name: 'Run all', exact: true }).waitFor()
  await page.waitForFunction(() => !document.querySelector('button.primary')?.disabled, null, { timeout: 30000 })
  await page.getByRole('button', { name: 'Run all', exact: true }).click()
  await page.waitForFunction(() => {
    const cell = document.querySelector('.nb-cell.code')
    return cell?.classList.contains('ok') || cell?.classList.contains('error')
  }, null, { timeout: 240000 })
  const output = await page.locator('.nb-cell.code').innerText()
  console.log(output.slice(-700))
  assert.equal(await page.locator('.nb-cell.code.error').count(), 0, output)
  assert.match(output, /Held-out accuracy:/)
  const accuracy = Number(/Held-out accuracy: ([\d.]+)%/.exec(output)?.[1])
  assert.ok(accuracy > 90, `Held-out accuracy was ${accuracy}%`)
  assert.equal(await page.locator('.mp-display[data-mime="image/svg+xml"]').count(), 2)
  const canvas = page.getByLabel('Draw a digit from 0 to 9 with your mouse or touch')
  await canvas.scrollIntoViewIfNeeded()
  const box = await canvas.boundingBox()
  await page.mouse.move(box.x + box.width * .5, box.y + box.height * .15)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * .5, box.y + box.height * .85, { steps: 20 })
  await page.mouse.up()
  assert.notEqual(await page.locator('.digit-answer').innerText(), '—')
  assert.ok(await page.locator('meter').evaluateAll(ms => ms.some(m => m.value > .1)))
  await page.screenshot({ path: '/tmp/mini-digit-lab.png' })
  await page.getByRole('button', { name: 'Clear drawing' }).click()
  assert.equal(await page.locator('.digit-answer').innerText(), '—')
  await page.setViewportSize({ width: 390, height: 844 })
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Mobile page has no horizontal overflow')
  assert.deepEqual(errors, [])
  await page.screenshot({ path: '/tmp/mini-digit-lab-mobile.png' })
  console.log(`ok: HTTP download, Mini training (${accuracy}%), SVG charts, drawing inference, clear and mobile layout`)
} finally { await browser.close() }
