import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  assembleProgram, deleteCell, insertCell, mapDiagnostics, moveCell, newCell, parseNotebook, serializeNotebook, splitCell,
  type Cell,
} from '../.vitepress/theme/playground/notebook.ts'
import { packText, unpackText, readHash, hashFor } from '../.vitepress/theme/playground/share.ts'

const kinds = (src: string) => splitCell(src).map((c) => c.kind)

test('splitCell: declarations, imports and statements', () => {
  const src = `import mini.memory.*
@file:MemoryMode(Mode.Strict)

class Point(val x: Int, val y: Int) {
    fun sum() = x + y
}

val limit = 10
println("hi \${limit}")
fun twice(x: Int): Int =
    x * 2
println(twice(4))`
  assert.deepEqual(kinds(src), ['import', 'fileAnnotation', 'decl', 'decl', 'stmt', 'decl', 'stmt'])
})

test('splitCell: modifiers, annotations, generics and extension functions are declarations', () => {
  assert.deepEqual(
    kinds(`data class P(val a: Int)
private fun <T> id(x: T): T = x
fun String.shout() = uppercase()
@Suppress("unused")
internal object Counter { var n = 0 }
sealed interface S
enum class E { A, B }
typealias Ints = List<Int>
const val K = 1`),
    ['decl', 'decl', 'decl', 'decl', 'decl', 'decl', 'decl', 'decl'],
  )
})

test('splitCell: expressions that look like declarations are statements', () => {
  assert.deepEqual(kinds('val (a, b) = Pair(1, 2)\nval f = fun(x: Int) = x\nobject : Runnable { override fun run() {} }'), ['stmt', 'decl', 'stmt'])
})

test('splitCell: continuation lines, strings, comments and braces do not split a statement', () => {
  const src = `val xs = listOf(1, 2, 3)
    .map { it * 2 }
    .filter { it > 2 }
val s = "a { ( [ "
val raw = """
  }}}
  fun not_a_decl() {}
"""
/* fun x() { */
if (xs.isEmpty()) println("e")
else println("n")
var i = 0
i++
println(i)`
  const chunks = splitCell(src)
  assert.deepEqual(chunks.map((c) => c.kind), ['decl', 'decl', 'decl', 'stmt', 'decl', 'stmt', 'stmt'])
  assert.equal(chunks[0].lines.length, 3)
  assert.equal(chunks[2].lines.length, 4)
})

test('splitCell: string templates with braces and nested quotes', () => {
  const src = `println("a \${listOf("}", "{").size} b")
println("next")`
  assert.deepEqual(kinds(src), ['stmt', 'stmt'])
})

test('splitCell: import with star does not swallow the next line', () => {
  assert.deepEqual(kinds('import a.b.*\nfun f() {}'), ['import', 'decl'])
})

test('splitCell: line positions point into the cell', () => {
  const c = splitCell('\n// comment\nfun a() {}\n\nprintln(1)')
  assert.equal(c[0].startLine, 1)
  assert.equal(c[0].lines[2], 'fun a() {}')
  assert.equal(c[1].startLine, 4)
})

const code = (s: string): Cell => newCell('code', s)

test('assembleProgram: declarations accumulate, statements of the target cell become main', () => {
  const cells = [
    code('import mini.memory.*\nclass A(val n: Int)\nval base = 40'),
    newCell('markdown', '# text'),
    code('fun f(a: A) = a.n + base\nprintln("never")'),
    code('import mini.memory.*\nprintln(f(A(2)))\nprintln("done")'),
  ]
  const a = assembleProgram(cells, 3)
  assert.equal(
    a.file.text,
    [
      'import mini.memory.*', 'class A(val n: Int)', '', 'val base = 40', '', 'fun f(a: A) = a.n + base', '',
      'fun main() {', 'println(f(A(2)))', 'println("done")', '}', '',
    ].join('\n'),
  )
  // the statement of cell 2 is not part of cell 3's program; imports are deduplicated
  assert.ok(!a.file.text.includes('never'))
  assert.equal((a.file.text.match(/import mini.memory/g) ?? []).length, 1)
})

test('assembleProgram: a later cell does not see statements of earlier cells, and earlier mains are dropped', () => {
  const cells = [code('fun main() { println("old") }'), code('println("new")')]
  const text = assembleProgram(cells, 1).file.text
  assert.ok(!text.includes('old'))
  assert.ok(text.includes('fun main() {\nprintln("new")\n}'))
})

test('assembleProgram: a cell with only its own main keeps it and generates none', () => {
  const text = assembleProgram([code('fun main() { println("x") }')], 0).file.text
  assert.equal((text.match(/fun main/g) ?? []).length, 1)
})

test('assembleProgram: empty cell is flagged', () => {
  assert.equal(assembleProgram([code('  \n')], 0).empty, true)
})

test('mapDiagnostics maps lines back to cells and clamps generated lines', () => {
  const cells = [code('class A\nclass B'), code('val a = A()\nval b = 1 +')]
  const asm = assembleProgram(cells, 1)
  const lines = asm.file.text.split('\n')
  const lineOf = (needle: string) => lines.findIndex((l) => l.includes(needle)) + 1
  const diag = (line: number, extra = {}) => ({
    file: 'notebook.kt', line, col: 3, endLine: line, endCol: 6, severity: 'error' as const, message: 'm', ...extra,
  })
  const [d1, d2, d3] = mapDiagnostics(
    [diag(lineOf('class B')), diag(lineOf('val b')), diag(lines.length - 1, { secondary: [{ ...diag(lineOf('class A')), label: 'here' }] })],
    asm,
    1,
  )
  assert.deepEqual([d1.cell, d1.line], [0, 2])
  assert.deepEqual([d2.cell, d2.line], [1, 2])
  assert.equal(d3.cell, 1)
  assert.deepEqual([d3.secondary![0].cell, d3.secondary![0].line], [0, 1])
})

test('.minb round trip and validation', () => {
  const nb = { title: 'T', cells: [newCell('markdown', '# hi'), newCell('code', 'println(1)')] }
  const back = parseNotebook(serializeNotebook(nb))
  assert.equal(back.title, 'T')
  assert.deepEqual(back.cells.map((c) => [c.type, c.source]), [['markdown', '# hi'], ['code', 'println(1)']])
  assert.throws(() => parseNotebook('nope'), /invalid JSON/)
  assert.throws(() => parseNotebook('{"format":"x"}'), /not a .minb/)
  assert.throws(() => parseNotebook('{"format":"minb","version":99,"cells":[]}'), /version 99/)
  assert.throws(() => parseNotebook('{"format":"minb","version":1,"cells":[{"type":"raw","source":""}]}'), /Cell 1/)
})

test('cell operations', () => {
  const a = newCell('code', 'a'), b = newCell('code', 'b'), c = newCell('code', 'c')
  let nb = { title: '', cells: [a, b, c] }
  assert.deepEqual(moveCell(nb, 0, 1).cells.map((x) => x.source), ['b', 'a', 'c'])
  assert.deepEqual(moveCell(nb, 0, -1), nb)
  assert.deepEqual(moveCell(nb, 2, 1), nb)
  assert.deepEqual(deleteCell(nb, 1).cells.map((x) => x.source), ['a', 'c'])
  assert.deepEqual(insertCell(nb, 1, newCell('code', 'x')).cells.map((x) => x.source), ['a', 'x', 'b', 'c'])
  assert.equal(new Set([a.id, b.id, c.id]).size, 3)
})

test('share links round trip, including non-ASCII and large text', async () => {
  const text = 'fun main() { println("grüße 🌍") }\n'.repeat(2000)
  const packed = await packText(text)
  assert.match(packed, /^z[A-Za-z0-9_-]+$/)
  assert.ok(packed.length < text.length / 10)
  assert.equal(await unpackText(packed), text)
  assert.equal(await readHash(await hashFor('x')), 'x')
  assert.equal(await readHash('#c=%%%'), undefined)
  assert.equal(await readHash('#c=zAAAA'), undefined)
  assert.equal(await readHash(''), undefined)
  // plain payloads (older browsers) are accepted
  assert.equal(await unpackText('p' + Buffer.from('abc').toString('base64url')), 'abc')
})
