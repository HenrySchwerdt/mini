import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EditorState, EditorSelection, type Transaction, type StateCommand } from '@codemirror/state'
import { insertNewlineAndIndent, indentLess } from '@codemirror/commands'
import { CompletionContext } from '@codemirror/autocomplete'
import { ensureSyntaxTree } from '@codemirror/language'
import { insertIndent, kotlinCompletions, kotlinEditing } from '../.vitepress/theme/playground/kotlin-editing.ts'

function editor(marked: string, readOnly = false) {
  const cursor = marked.indexOf('|')
  let state = EditorState.create({
    doc: marked.replace('|', ''), selection: { anchor: cursor },
    extensions: [kotlinEditing(), EditorState.readOnly.of(readOnly)],
  })
  return {
    get state() { return state },
    command(command: StateCommand) {
      return command({ state, dispatch: (tr: Transaction) => { state = tr.state } })
    },
    select(from: number, to: number) { state = state.update({ selection: EditorSelection.range(from, to) }).state },
  }
}

test('Enter splits a block with four-space indentation and aligns its closing brace', () => {
  const e = editor('fun main() {|}')
  e.command(insertNewlineAndIndent)
  assert.equal(e.state.doc.toString(), 'fun main() {\n    \n}')
  assert.equal(e.state.selection.main.head, 'fun main() {\n    '.length)
})

test('Enter keeps body indentation and handles nested blocks and multiline arguments', () => {
  for (const [before, after] of [
    ['fun main() {\n    println(1)|\n}', 'fun main() {\n    println(1)\n    \n}'],
    ['fun main() {\n    if (true) {|}\n}', 'fun main() {\n    if (true) {\n        \n    }\n}'],
    ['fun main() {\n    println(|)\n}', 'fun main() {\n    println(\n        \n    )\n}'],
  ]) {
    const e = editor(before)
    e.command(insertNewlineAndIndent)
    assert.equal(e.state.doc.toString(), after)
  }
})

test('Tab advances to a four-space stop without shifting existing code', () => {
  const e = editor('val a = |1')
  e.command(insertIndent)
  assert.equal(e.state.doc.toString(), 'val a =     1')
  const blank = editor('  |')
  blank.command(insertIndent)
  assert.equal(blank.state.doc.toString(), '    ')
})

test('Tab and Shift+Tab shift selected lines as one block', () => {
  const e = editor('|val a = 1\nval b = 2')
  e.select(0, e.state.doc.length)
  e.command(insertIndent)
  assert.equal(e.state.doc.toString(), '    val a = 1\n    val b = 2')
  e.command(indentLess)
  assert.equal(e.state.doc.toString(), 'val a = 1\nval b = 2')
})

test('indentation never modifies read-only code', () => {
  const e = editor('fun main() {|}', true)
  assert.equal(e.command(insertIndent), false)
  assert.equal(e.command(insertNewlineAndIndent), false)
  assert.equal(e.state.doc.toString(), 'fun main() {}')
})

test('Kotlin completion offers snippets and skips comments and strings', async () => {
  const e = editor('pri|')
  const result = await kotlinCompletions(new CompletionContext(e.state, e.state.selection.main.head, true))
  assert.ok(result?.options.some((option) => option.label === 'println' && typeof option.apply === 'function'))
  const readOnly = editor('pri|', true)
  assert.equal(await kotlinCompletions(new CompletionContext(readOnly.state, 3, true)), null)
  for (const source of ['// pri|', 'val text = "pri|"', '/* pri| */']) {
    const e = editor(source)
    ensureSyntaxTree(e.state, e.state.doc.length, 100)
    assert.equal(await kotlinCompletions(new CompletionContext(e.state, e.state.selection.main.head, true)), null)
  }
})
