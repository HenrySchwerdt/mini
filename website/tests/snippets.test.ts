import { test } from 'node:test'
import assert from 'node:assert/strict'
import MarkdownIt from 'markdown-it'
import {
  decideCell, hasMain, meetsExpectation, parseCheckFlag, parseFenceInfo, programFor, sameOutput, type CellSpec,
} from '../.vitepress/theme/snippets.ts'
import { miniCellsPlugin } from '../.vitepress/mini-cells.ts'

const MAIN = 'fun main() {\n    println("hi")\n}\n'

test('parseFenceInfo: language, flags and key=value', () => {
  const i = parseFenceInfo('kotlin{1,2} run check=M0102')
  assert.equal(i.lang, 'kotlin')
  assert.equal(i.flags.get('run'), true)
  assert.equal(i.flags.get('check'), 'M0102')
})

test('hasMain', () => {
  assert.ok(hasMain(MAIN))
  assert.ok(hasMain('import x\n\nfun main(args: Array<String>) {}'))
  assert.ok(!hasMain('fun mainly() {}'))
  assert.ok(!hasMain('// fun main() {}'))
})

test('decideCell: inline fences', () => {
  const d = (info: string, source: string, extra = {}) => decideCell({ info, source, notebookPage: false, ...extra })
  assert.equal(d('kotlin', MAIN)?.kind, 'run')
  assert.equal(d('kotlin', 'val x = 1'), undefined, 'a fragment is not runnable unless marked')
  assert.equal(d('kotlin run', 'val x = 1')?.kind, 'run')
  assert.equal(d('kotlin norun', MAIN), undefined)
  assert.equal(d('text', MAIN), undefined)
  assert.deepEqual(d('kotlin check=M0102', MAIN)?.expect, { kind: 'error', code: 'M0102' })
  assert.deepEqual(d('kotlin check', MAIN)?.expect, { kind: 'ok' })
  assert.equal(d('kotlin', MAIN, { output: 'hi\n' })?.expected, 'hi\n')
})

test('decideCell: samples follow the files next to them', () => {
  const base = { info: 'kotlin', source: MAIN, notebookPage: false }
  assert.equal(decideCell({ ...base, sample: { path: 'a.kt', out: 'hi\n', norun: false } })?.kind, 'run')
  assert.deepEqual(decideCell({ ...base, sample: { path: 'a.kt', check: 'error M0102\n', norun: false } })?.expect, { kind: 'error', code: 'M0102' })
  assert.equal(decideCell({ ...base, sample: { path: 'a.kt', out: 'hi\n', norun: true } }), undefined)
  assert.equal(decideCell({ ...base, sample: { path: 'a.kt', norun: false } }), undefined)
})

test('decideCell: notebook pages chain fragments, not complete programs', () => {
  const nb = (info: string, source: string) => decideCell({ info, source, notebookPage: true })
  assert.equal(nb('kotlin run', 'val x = 1')?.chain, true)
  assert.equal(nb('kotlin', 'val x = 1')?.kind, 'run', 'fragments are cells on notebook pages')
  assert.equal(nb('kotlin norun', 'val x = 1'), undefined)
  assert.equal(nb('kotlin', MAIN)?.chain, false)
  assert.equal(nb('kotlin run fresh', 'val x = 1')?.chain, false)
  assert.equal(decideCell({ info: 'kotlin run chain', source: 'val x = 1', notebookPage: false })?.chain, true)
})

const spec = (source: string, chain: boolean, kind: 'run' | 'check' = 'run'): CellSpec => ({ index: 0, kind, chain, exercise: false, source })

test('programFor: complete programs stay as they are, fragments are wrapped', () => {
  assert.equal(programFor([spec(MAIN, false)], [MAIN], 0).file.text, MAIN)
  const t = programFor([spec('println(1 + 2)', false)], ['println(1 + 2)'], 0).file.text
  assert.match(t, /fun main\(\) \{\nprintln\(1 \+ 2\)\n\}/)
})

test('programFor: chain cells see declarations of the cells above, not their statements', () => {
  const cells = [spec('class P(val x: Int)\nprintln("first")', true), spec('println(P(2).x)', true)]
  const text = programFor(cells, cells.map((c) => c.source), 1).file.text
  assert.match(text, /class P/)
  assert.match(text, /println\(P\(2\)\.x\)/)
  assert.doesNotMatch(text, /first/)
})

test('programFor: diagnostics map back to the cell lines', () => {
  const p = programFor([spec('val a = 1\nprintln(a + "x")', false)], ['val a = 1\nprintln(a + "x")'], 0)
  const text = p.file.text.split('\n')
  const line = text.findIndex((l) => l.includes('println')) + 1
  const [m] = p.map([{ file: 'main.kt', line, col: 9, endLine: line, endCol: 12, severity: 'error', message: 'x' }])
  assert.equal(m.cell, p.target)
  assert.equal(m.line, 2)
})

test('sameOutput ignores trailing whitespace only', () => {
  assert.ok(sameOutput('a \nb\n\n', 'a\nb'))
  assert.ok(!sameOutput('a\nb', 'a\nc'))
})

test('meetsExpectation', () => {
  const err = { code: 'M0102', severity: 'error' as const }
  assert.ok(meetsExpectation([err], { kind: 'error', code: 'M0102' }))
  assert.ok(!meetsExpectation([err], { kind: 'error', code: 'M0103' }))
  assert.ok(!meetsExpectation([err], { kind: 'ok' }))
  assert.ok(meetsExpectation([], { kind: 'ok' }))
  assert.ok(meetsExpectation([{ code: 'M0202', severity: 'warning' }], parseCheckFlag('warn:M0202')))
})

test('plugin: wraps runnable fences, collects cells, takes ```output as the expectation', () => {
  const md = new MarkdownIt()
  md.use(miniCellsPlugin)
  const env: { miniCells?: CellSpec[]; frontmatter?: object } = { frontmatter: {} }
  const html = md.render('```kotlin\n' + MAIN + '```\n\n```output\nhi\n```\n\n```kotlin\nval x = 1\n```\n\n```kotlin check=M0101\n' + MAIN + '```\n', env)
  assert.equal(env.miniCells?.length, 2)
  assert.equal(env.miniCells?.[0].expected, 'hi\n')
  assert.equal(env.miniCells?.[1].kind, 'check')
  assert.equal(html.match(/<MiniCell /g)?.length, 2)
  assert.doesNotMatch(html, /output/)
})
