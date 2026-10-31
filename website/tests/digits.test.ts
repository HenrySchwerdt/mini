import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { datasetUrl, notebookDatasetUrl, fetchDataset } from '../.vitepress/theme/playground/datasets.ts'
import { parseDigitModel, digitProbabilities } from '../.vitepress/theme/playground/digits.ts'
import { assembleProgram, newCell } from '../.vitepress/theme/playground/notebook.ts'

test('model output validates dimensions, version and finite numeric parameters', () => {
  const m = { version: 1, weights: Array(640).fill(0), bias: Array(10).fill(0) }
  assert.ok(parseDigitModel(JSON.stringify(m)))
  for (const bad of [null, {}, { ...m, version: 2 }, { ...m, weights: [0] }, { ...m, bias: Array(10).fill('1') }, { ...m, bias: Array(10).fill(null) }]) assert.equal(parseDigitModel(JSON.stringify(bad)), null)
  assert.equal(parseDigitModel('invalid'), null)
})

test('inference uses output-major weights and stable softmax', () => {
  const model = { version: 1 as const, weights: Array(640).fill(0), bias: Array(10).fill(1000) }
  model.weights[3 * 64 + 17] = 5
  const pixels = Array(64).fill(0); pixels[17] = 1
  const p = digitProbabilities(model, pixels)
  assert.equal(p.indexOf(Math.max(...p)), 3)
  assert.ok(Math.abs(p.reduce((a, b) => a + b) - 1) < 1e-12)
  assert.ok(p[3] > .94)
  assert.throws(() => digitProbabilities(model, [1]))
  assert.throws(() => digitProbabilities(model, Array(64).fill(NaN)))
})

test('dataset directives require one HTTPS URL without credentials', () => {
  assert.equal(datasetUrl('println(1)'), null)
  assert.equal(datasetUrl('// %dataset https://example.com/data.csv\nreadln()'), 'https://example.com/data.csv')
  assert.throws(() => datasetUrl('// %dataset http://example.com/data'))
  assert.throws(() => datasetUrl('// %dataset https://user:secret@example.com/data'))
  assert.throws(() => datasetUrl('// %dataset https://example.com/a\n// %dataset https://example.com/b'))
})

test('dataset download handles gzip, HTTP errors, limits and cancellation', async () => {
  const original = globalThis.fetch
  try {
    const gzip = await new Response(new Blob(['1,2,3\n']).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer()
    globalThis.fetch = async () => new Response(gzip)
    assert.equal(await fetchDataset('https://example.com/data.gz', new AbortController().signal), '1,2,3\n')
    globalThis.fetch = async () => new Response('missing', { status: 404 })
    await assert.rejects(fetchDataset('https://example.com/data', new AbortController().signal), /404/)
    globalThis.fetch = async () => new Response('x'.repeat(2 * 1024 * 1024 + 1))
    await assert.rejects(fetchDataset('https://example.com/data', new AbortController().signal), /2 MiB/)
    globalThis.fetch = async () => new Response('data')
    const abort = new AbortController(); abort.abort()
    await assert.rejects(fetchDataset('https://example.com/data', abort.signal), /abort/i)
  } finally { globalThis.fetch = original }
})

test('short experiment cells assemble both models and all three plot APIs', () => {
  const source = readFileSync(new URL('../.vitepress/theme/playground/digit-training.kt', import.meta.url), 'utf8')
  const cells = source.split(/^\/\/ %% .+\r?\n/gm).slice(1).map(s => newCell('code', s.trim()))
  const asm = assembleProgram(cells, cells.length - 1)
  assert.equal(cells.length, 11)
  assert.ok(cells.every(c => c.source.split('\n').length <= 22), 'Keep code cells small')
  assert.match(asm.file.text, /x @ weight.T/)
  assert.match(asm.file.text, /Linear\(64, 10\)/)
  for (const api of ['plt.plot', 'axes[0].plot', 'plot {']) assert.ok(asm.file.text.includes(api))
  assert.equal((asm.file.text.match(/fun main\(/g) ?? []).length, 1)
  assert.ok(asm.file.text.indexOf('optimizer.step()') < asm.file.text.indexOf('val accuracy = noGrad'))
  assert.match(asm.file.text, /application\/vnd.mini.digit-classifier\+json/)
})

test('dataset source escapes Kotlin interpolation, quotes, controls and backslashes', async () => {
  const { datasetSource } = await import('../.vitepress/theme/playground/datasets.ts')
  const source = datasetSource('a"\n\\$name ${1+1}\r\t')
  assert.match(source.text, /package mini.notebook/)
  assert.ok(source.text.includes('\\$name \\${1+1}'))
  assert.ok(source.text.includes('\\n'))
  assert.ok(source.text.includes('\\"'))
})

test('stop cancels a dataset whose server never finishes the body', async () => {
  const original = globalThis.fetch
  let cancelled = false
  try {
    globalThis.fetch = async () => new Response(new ReadableStream({ cancel() { cancelled = true } }))
    const controller = new AbortController()
    const pending = fetchDataset('https://example.com/stalled', controller.signal)
    await new Promise(resolve => setTimeout(resolve, 0))
    controller.abort()
    await assert.rejects(pending, /abort/i)
    assert.equal(cancelled, true)
  } finally { globalThis.fetch = original }
})

test('area downsampling preserves thin strokes between grid sample centers', async () => {
  const { drawingPixels } = await import('../.vitepress/theme/playground/digits.ts')
  const rgba = new Uint8ClampedArray(256 * 256 * 4)
  for (let y = 0; y < 256; y++) for (let x = 119; x < 137; x++) rgba[(y * 256 + x) * 4 + 3] = 255
  const pixels = drawingPixels(rgba)
  assert.equal(pixels.length, 64)
  assert.ok(pixels.every(x => x >= 0 && x <= 1))
  assert.equal(pixels.filter(x => x > 0).length, 16)
  assert.ok(Math.abs(pixels[3] - 9 / 32) < 1e-12)
})

test('dataset directives are inherited, replaced and never read from future or markdown cells', () => {
  const cells = [newCell('code', '// %dataset https://example.com/a'), newCell('markdown', '// %dataset https://example.com/ignored'), newCell('code', 'println(1)'), newCell('code', '// %dataset https://example.com/b')]
  assert.equal(notebookDatasetUrl(cells, 2), 'https://example.com/a')
  assert.equal(notebookDatasetUrl(cells, 3), 'https://example.com/b')
  assert.equal(notebookDatasetUrl([newCell('code', 'println(1)'), ...cells], 0), null)
})
