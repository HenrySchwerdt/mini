import { EditorSelection, countColumn, type Extension, type StateCommand } from '@codemirror/state'
import { indentMore } from '@codemirror/commands'
import { indentUnit, getIndentUnit, StreamLanguage, syntaxTree } from '@codemirror/language'
import { autocompletion, completeAnyWord, completeFromList, snippetCompletion, type CompletionContext } from '@codemirror/autocomplete'
import { kotlin } from '@codemirror/legacy-modes/mode/clike'

export const kotlinLanguage = StreamLanguage.define(kotlin)

const snippets = [
  snippetCompletion('fun ${name}(${parameters}) {\n\t${}\n}', { label: 'fun', detail: 'function', type: 'snippet', boost: 5 }),
  snippetCompletion('if (${condition}) {\n\t${}\n}', { label: 'if', detail: 'conditional block', type: 'snippet', boost: 5 }),
  snippetCompletion('for (${item} in ${items}) {\n\t${}\n}', { label: 'for', detail: 'loop', type: 'snippet', boost: 5 }),
  snippetCompletion('while (${condition}) {\n\t${}\n}', { label: 'while', detail: 'loop', type: 'snippet', boost: 5 }),
  snippetCompletion('when (${value}) {\n\t${}\n}', { label: 'when', detail: 'branches', type: 'snippet', boost: 5 }),
  snippetCompletion('class ${Name}(${properties}) {\n\t${}\n}', { label: 'class', detail: 'class', type: 'snippet', boost: 5 }),
  snippetCompletion('print(${value})${}', { label: 'print', detail: 'print a value', type: 'snippet', boost: 5 }),
  snippetCompletion('println(${value})${}', { label: 'println', detail: 'print a line', type: 'snippet', boost: 5 }),
]
const words = 'val var return else in is as null true false this super import package interface object data sealed enum override open abstract private public internal protected const lateinit lazy by get set try catch finally throw inline noinline crossinline reified operator companion init constructor context typealias break continue Int Long Double Boolean Char String Unit Any Nothing List MutableList Map MutableMap Set MutableSet Array IntArray Heap Arena Pool'.split(' ')
const keywords = completeFromList([
  ...snippets,
  ...'listOf mutableListOf mapOf mutableMapOf setOf mutableSetOf emptyList arrayOf check require repeat run let'.split(' ').map((label) => ({ label, type: 'function' })),
  ...words.map((label) => ({ label, type: /^[A-Z]/.test(label) ? 'type' : 'keyword' })),
])

function inCode(context: CompletionContext): boolean {
  return !context.state.readOnly && !/string|comment/i.test(syntaxTree(context.state).resolveInner(context.pos, -1).name)
}

export function kotlinCompletions(context: CompletionContext) {
  return inCode(context) ? keywords(context) : null
}

function documentCompletions(context: CompletionContext) {
  return inCode(context) ? completeAnyWord(context) : null
}

/** Tab inserts spaces at the cursor; selected lines are shifted as a block. */
export const insertIndent: StateCommand = ({ state, dispatch }) => {
  if (state.readOnly) return false
  if (state.selection.ranges.some((range) => !range.empty)) return indentMore({ state, dispatch })
  const unit = getIndentUnit(state)
  const changes = state.changeByRange((range) => {
    const line = state.doc.lineAt(range.head)
    const column = countColumn(line.text.slice(0, range.head - line.from), state.tabSize)
    const spaces = ' '.repeat(unit - column % unit)
    return {
      changes: { from: range.head, insert: spaces },
      range: EditorSelection.cursor(range.head + spaces.length),
    }
  })
  dispatch(state.update(changes, { scrollIntoView: true, userEvent: 'input.indent' }))
  return true
}

export function kotlinEditing(): Extension {
  return [
    kotlinLanguage,
    indentUnit.of('    '),
    autocompletion({ override: [kotlinCompletions, documentCompletions] }),
  ]
}
