import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DisplayParser, OutputSink, parseTable, placeholder, splitOutput, type Segment } from '../.vitepress/theme/playground/display.ts'

const b64 = (s: string) => Buffer.from(s, 'utf8').toString('base64')
const bundle = (mime: string, data: string, end = '\u0007') => `\u001b]5379;mini-display;${mime};${b64(data)}${end}`

test('plain text passes through untouched', () => {
  assert.deepEqual(splitOutput('hello\nworld\n'), { text: 'hello\nworld\n', bundles: [] })
})

test('a bundle between text', () => {
  const r = splitOutput(`before ${bundle('image/svg+xml', '<svg xmlns="http://www.w3.org/2000/svg"/>')} after\n`)
  assert.equal(r.text, `before \n${placeholder('image/svg+xml')}\n after\n`)
  assert.deepEqual(r.bundles, [{ mime: 'image/svg+xml', data: '<svg xmlns="http://www.w3.org/2000/svg"/>' }])
})

test('UTF-8 payloads and the ST terminator', () => {
  const r = splitOutput(bundle('text/markdown', 'héllo ✓ 😀', '\u001b\\'))
  assert.equal(r.bundles[0].data, 'héllo ✓ 😀')
  assert.equal(r.text, placeholder('text/markdown') + '\n')
})

test('a bundle split at every possible position', () => {
  const full = `a${bundle('text/html', '<b>x</b>')}b`
  for (let cut = 0; cut <= full.length; cut++) {
    const p = new DisplayParser()
    const pieces = [...p.feed(full.slice(0, cut)), ...p.feed(full.slice(cut)), ...p.flush()]
    const text = pieces.map((x) => ('text' in x ? x.text : '#')).join('')
    assert.equal(text, 'a#b', `cut at ${cut}`)
  }
})

test('other escape sequences are plain text', () => {
  const colored = '\u001b[31mred\u001b[0m\n'
  assert.equal(splitOutput(colored).text, colored)
  assert.equal(splitOutput('\u001b]0;title\u0007x').text, '\u001b]0;title\u0007x')
})

test('an unterminated bundle becomes text at the end of the stream', () => {
  const partial = `x\u001b]5379;mini-display;image/svg+xml;PHN2Zz4=`
  const p = new DisplayParser()
  assert.deepEqual(p.feed(partial), [{ text: 'x' }])
  assert.deepEqual(p.flush(), [{ text: partial.slice(1) }])
  const lone = new DisplayParser()
  assert.deepEqual(lone.feed('a\u001b]53'), [{ text: 'a' }])
  assert.deepEqual(lone.flush(), [{ text: '\u001b]53' }])
})

test('invalid base64 is reported, not thrown', () => {
  const r = splitOutput('\u001b]5379;mini-display;text/html;***\u0007')
  assert.equal(r.text, placeholder('invalid'))
  assert.equal(r.bundles.length, 0)
})

test('the sink merges text of one stream and keeps bundles in order', () => {
  const segments: Segment[] = []
  const sink = new OutputSink(segments)
  sink.push('out', 'one ')
  sink.push('out', 'two\n')
  sink.push('err', 'warn\n')
  const svg = bundle('image/svg+xml', '<svg/>')
  sink.push('out', svg.slice(0, 20))
  sink.push('out', svg.slice(20) + 'tail\n')
  sink.end()
  assert.deepEqual(segments, [
    { type: 'out', text: 'one two\n' },
    { type: 'err', text: 'warn\n' },
    { type: 'display', mime: 'image/svg+xml', data: '<svg/>' },
    { type: 'out', text: 'tail\n' },
  ])
  assert.equal(sink.stdout, `one two\n${placeholder('image/svg+xml')}\ntail\n`)
})

test('tables', () => {
  assert.deepEqual(parseTable('{"columns":["a","b"],"rows":[["1","2"],[3,"x"]]}'), { columns: ['a', 'b'], rows: [['1', '2'], ['3', 'x']] })
  assert.equal(parseTable('{"columns":1}'), null)
  assert.equal(parseTable('nope'), null)
})
