// Loads the wasm library in Chromium (Playwright) from a page served over HTTP and checks what the page computed.
//   node test-browser.mjs <dir with mathlib.mjs, mathlib.wasm>
// Exit code 2 when Playwright or Chromium is not available (the caller decides whether that is a failure).
import { createServer } from 'node:http'
import { readFileSync, existsSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join, resolve, extname } from 'node:path'
import assert from 'node:assert/strict'

const dir = resolve(process.argv[2])
let chromium
try {
    chromium = createRequire(import.meta.url)('playwright').chromium
} catch (e) {
    console.log('browser test skipped: no playwright (' + e.message.split('\n')[0] + ')')
    process.exit(2)
}

const html = `<!doctype html>
<meta charset="utf-8">
<title>mathlib</title>
<script type="module">
  import { load, Counter, MiniError } from './mathlib.mjs'
  const logs = []
  try {
    const lib = await load(undefined, { stdout: s => logs.push(s) })
    const c = lib.newCounter(5)
    const result = {
      add: lib.add(2, 3),
      big: String(lib.addLong(2n ** 40n, 1n)),
      reversed: lib.reverse('h\\u00e9llo \\u4e16\\u754c'),
      greeting: lib.greet('browser'),
      sum: lib.sum([1, 2, 3]),
      scaled: Array.from(lib.scale([1, 2], 3)),
      counter: [lib.increment(c), lib.increment(c)],
      isCounter: c instanceof Counter,
      words: lib.words('one two three'),
    }
    c.dispose()
    try { lib.fail('boom') } catch (e) { result.error = e.message; result.isMiniError = e instanceof MiniError }
    lib.log('hi')
    result.logs = logs
    window.RESULT = result
  } catch (e) {
    window.RESULT = { failure: String(e && e.stack || e) }
  }
</script>
`

const types = { '.mjs': 'text/javascript', '.js': 'text/javascript', '.wasm': 'application/wasm', '.html': 'text/html' }
const server = createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost')
    if (url.pathname === '/' || url.pathname === '/index.html') {
        res.writeHead(200, { 'content-type': 'text/html' })
        res.end(html)
        return
    }
    const file = join(dir, url.pathname.replace(/^\/+/, ''))
    if (!file.startsWith(dir) || !existsSync(file)) { res.writeHead(404); res.end('not found'); return }
    res.writeHead(200, { 'content-type': types[extname(file)] || 'application/octet-stream' })
    res.end(readFileSync(file))
})
await new Promise(r => server.listen(0, '127.0.0.1', r))
const port = server.address().port

let browser
try {
    browser = await chromium.launch()
} catch (e) {
    server.close()
    console.log('browser test skipped: cannot start Chromium (' + e.message.split('\n')[0] + ')')
    process.exit(2)
}
let failed = null
try {
    const page = await browser.newPage()
    const errors = []
    page.on('pageerror', e => errors.push(String(e)))
    await page.goto(`http://127.0.0.1:${port}/`)
    await page.waitForFunction(() => window.RESULT !== undefined, null, { timeout: 30000 })
    const r = await page.evaluate(() => window.RESULT)
    assert.equal(r.failure, undefined, r.failure)
    assert.deepEqual(errors, [])
    assert.equal(r.add, 5)
    assert.equal(r.big, String(2n ** 40n + 1n))
    assert.equal(r.reversed, '界世 olléh')
    assert.equal(r.greeting, 'Hello, wasm, browser!')
    assert.equal(r.sum, 6)
    assert.deepEqual(r.scaled, [3, 6])
    assert.deepEqual(r.counter, [6, 7])
    assert.equal(r.isCounter, true)
    assert.equal(r.words, 3)
    assert.equal(r.error, 'java.lang.IllegalStateException: boom')
    assert.equal(r.isMiniError, true)
    assert.deepEqual(r.logs, ['log: hi\n'])
    console.log('wasm-lib browser test (Chromium): ok')
} catch (e) {
    failed = e
} finally {
    await browser.close()
    server.close()
}
if (failed) { console.error(failed); process.exit(1) }
