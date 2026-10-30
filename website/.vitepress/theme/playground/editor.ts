import { EditorState, StateEffect, StateField, type Text } from '@codemirror/state'
import {
  Decoration, EditorView, drawSelection, highlightActiveLine, highlightActiveLineGutter, keymap, lineNumbers,
  rectangularSelection, crosshairCursor, highlightSpecialChars,
  type DecorationSet,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentLess } from '@codemirror/commands'
import { HighlightStyle, bracketMatching, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { acceptCompletion, closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { search, searchKeymap, highlightSelectionMatches } from '@codemirror/search'
import { insertIndent, kotlinEditing } from './kotlin-editing.ts'
import { lintGutter, setDiagnostics, type Diagnostic as CmDiagnostic } from '@codemirror/lint'
import { tags as t } from '@lezer/highlight'
import type { Diagnostic } from './types.ts'

export interface EditorOptions {
  parent: HTMLElement
  doc: string
  /** Called on every document change. */
  onChange?: (doc: string) => void
  /** Cmd/Ctrl+Enter. */
  onRun?: () => void
  /** Link target for a diagnostic code, e.g. `/mini/reference/diagnostics/M0102`. */
  docsUrl?: (code: string) => string
  /** The editor fits its content (notebook cells) instead of filling its parent. */
  autoHeight?: boolean
  /** File name that diagnostics must belong to; others are ignored. */
  fileName?: string
  readOnly?: boolean
  placeholderLabel?: string
}

export interface MiniEditor {
  view: EditorView
  getDoc(): string
  setDoc(text: string): void
  setDiagnostics(diags: Diagnostic[]): void
  /** Moves the cursor to a 1-based line/column and scrolls it into view. */
  reveal(line: number, col: number): void
  focus(): void
  destroy(): void
}

// Syntax colors come from CSS variables (see playground.css) so light and dark theme need no extension swap.
const highlight = HighlightStyle.define([
  { tag: [t.keyword, t.modifier, t.operatorKeyword, t.controlKeyword, t.definitionKeyword], class: 'tok-keyword' },
  { tag: [t.string, t.special(t.string), t.character], class: 'tok-string' },
  { tag: [t.number, t.bool, t.null, t.atom], class: 'tok-number' },
  { tag: [t.comment, t.lineComment, t.blockComment], class: 'tok-comment' },
  { tag: [t.typeName, t.className, t.namespace], class: 'tok-type' },
  { tag: [t.function(t.variableName), t.function(t.propertyName), t.definition(t.variableName)], class: 'tok-function' },
  { tag: [t.meta, t.annotation], class: 'tok-meta' },
  { tag: [t.operator, t.punctuation], class: 'tok-punct' },
])

const baseTheme = EditorView.theme({
  '&': { height: '100%', color: 'var(--mp-fg)', backgroundColor: 'var(--mp-bg)', fontSize: '14px' },
  '.cm-scroller': { fontFamily: 'var(--vp-font-family-mono)', lineHeight: '1.55', overflow: 'auto' },
  '.cm-content': { caretColor: 'var(--mp-fg)', padding: '8px 0' },
  '.cm-cursor': { borderLeftColor: 'var(--mp-fg)' },
  '&.cm-focused': { outline: 'none' },
  '.cm-gutters': { backgroundColor: 'var(--mp-gutter-bg)', color: 'var(--mp-gutter-fg)', border: 'none' },
  '.cm-activeLine': { backgroundColor: 'var(--mp-active-line)' },
  '.cm-activeLineGutter': { backgroundColor: 'var(--mp-active-line)' },
  '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: 'var(--mp-selection) !important' },
  '.cm-selectionMatch': { backgroundColor: 'var(--mp-selection)' },
  '.cm-searchMatch': { backgroundColor: 'var(--vp-c-yellow-soft)' },
  '.cm-searchMatch.cm-searchMatch-selected': { outline: '1px solid var(--vp-c-brand-1)' },
  '.cm-panels': { backgroundColor: 'var(--vp-c-bg-soft)', color: 'var(--mp-fg)' },
  '.cm-search input, .cm-search button': { font: 'inherit', color: 'inherit' },
  '.cm-matchingBracket': { backgroundColor: 'var(--mp-selection)', outline: 'none' },
  '.cm-tooltip': { border: '1px solid var(--vp-c-divider)', backgroundColor: 'var(--vp-c-bg-elv)', color: 'var(--vp-c-text-1)', borderRadius: '6px', boxShadow: 'var(--vp-shadow-3)' },
  '.cm-tooltip.cm-tooltip-lint': { maxWidth: '520px' },
  '.cm-diagnostic': { padding: '8px 12px', borderLeft: '4px solid var(--vp-c-divider)', fontFamily: 'var(--vp-font-family-base)', fontSize: '13px' },
  '.cm-diagnostic-error': { borderLeftColor: 'var(--vp-c-danger-1)' },
  '.cm-diagnostic-warning': { borderLeftColor: 'var(--vp-c-warning-1)' },
  '.cm-lintRange-error': { backgroundImage: 'none', textDecoration: 'underline wavy var(--vp-c-danger-1)', textUnderlineOffset: '3px' },
  '.cm-lintRange-warning': { backgroundImage: 'none', textDecoration: 'underline wavy var(--vp-c-warning-1)', textUnderlineOffset: '3px' },
  '.cm-mini-secondary': { textDecoration: 'underline dotted var(--vp-c-text-3)', textUnderlineOffset: '3px' },
})

const autoHeightTheme = EditorView.theme({
  '&': { height: 'auto' },
  '.cm-scroller': { overflow: 'auto', maxHeight: '60vh' },
})

// ---- secondary spans ----

const setSecondary = StateEffect.define<{ from: number; to: number }[]>()
const secondaryField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(value, tr) {
    value = value.map(tr.changes)
    for (const e of tr.effects) {
      if (e.is(setSecondary)) {
        value = Decoration.set(e.value.filter((r) => r.to > r.from).map((r) => Decoration.mark({ class: 'cm-mini-secondary' }).range(r.from, r.to)), true)
      }
    }
    return value
  },
  provide: (f) => EditorView.decorations.from(f),
})

function offset(doc: Text, line: number, col: number): number {
  const l = doc.line(Math.max(1, Math.min(line, doc.lines)))
  return Math.min(l.from + Math.max(0, col - 1), l.to)
}

/** Converts a toolchain diagnostic to a CodeMirror range. Zero-width ranges are widened so they stay visible. */
export function toRange(doc: Text, d: { line: number; col: number; endLine: number; endCol: number }): { from: number; to: number } {
  const from = offset(doc, d.line, d.col)
  let to = offset(doc, d.endLine, d.endCol)
  if (to <= from) {
    const l = doc.lineAt(from)
    to = from < l.to ? from + 1 : l.to
    if (to === from && from > l.from) return { from: from - 1, to }
  }
  return { from, to }
}

function renderDiagnostic(d: Diagnostic, docsUrl: ((code: string) => string) | undefined, view: EditorView, doc: Text): Node {
  const root = document.createElement('div')
  root.className = 'mp-diag'
  const head = document.createElement('div')
  head.className = 'mp-diag-head'
  if (d.code) {
    const a = document.createElement(docsUrl ? 'a' : 'span')
    a.className = 'mp-diag-code'
    a.textContent = d.code
    if (docsUrl) {
      ;(a as HTMLAnchorElement).href = docsUrl(d.code)
      ;(a as HTMLAnchorElement).target = '_blank'
      ;(a as HTMLAnchorElement).rel = 'noopener'
      a.title = `Open the reference page for ${d.code}`
    }
    head.append(a)
  }
  const sev = document.createElement('span')
  sev.className = `mp-diag-sev mp-sev-${d.severity}`
  sev.textContent = d.severity
  head.append(sev)
  root.append(head)

  const msg = document.createElement('div')
  msg.className = 'mp-diag-msg'
  msg.textContent = d.message
  root.append(msg)

  for (const s of d.secondary ?? []) {
    const row = document.createElement('div')
    row.className = 'mp-diag-note'
    const link = document.createElement('button')
    link.type = 'button'
    link.className = 'mp-diag-loc'
    link.textContent = `line ${s.line}`
    link.addEventListener('mousedown', (e) => {
      e.preventDefault()
      const pos = offset(doc, s.line, s.col)
      view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) })
      view.focus()
    })
    row.append(link, document.createTextNode(` ${s.label}`))
    root.append(row)
  }
  if (d.help) {
    const help = document.createElement('div')
    help.className = 'mp-diag-help'
    const b = document.createElement('strong')
    b.textContent = 'help: '
    help.append(b, document.createTextNode(d.help))
    root.append(help)
  }
  return root
}

export function createEditor(opts: EditorOptions): MiniEditor {
  const runKeys = keymap.of([
    {
      key: 'Mod-Enter',
      run: () => {
        opts.onRun?.()
        return true
      },
    },
  ])
  const state = EditorState.create({
    doc: opts.doc,
    extensions: [
      runKeys,
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      history(),
      drawSelection(),
      rectangularSelection(),
      crosshairCursor(),
      highlightSpecialChars(),
      EditorState.allowMultipleSelections.of(true),
      search({ top: true }),
      highlightSelectionMatches(),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      keymap.of([
        ...closeBracketsKeymap, ...searchKeymap, ...defaultKeymap, ...historyKeymap,
        { key: 'Tab', run: (view) => acceptCompletion(view) || insertIndent(view), shift: indentLess },
      ]),
      kotlinEditing(),
      syntaxHighlighting(highlight),
      lintGutter(),
      secondaryField,
      baseTheme,
      opts.autoHeight ? autoHeightTheme : [],
      EditorState.tabSize.of(4),
      EditorState.readOnly.of(!!opts.readOnly),
      EditorView.contentAttributes.of({ 'aria-label': opts.placeholderLabel ?? 'Mini source code', spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off' }),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) opts.onChange?.(u.state.doc.toString())
      }),
    ],
  })
  const view = new EditorView({ state, parent: opts.parent })

  return {
    view,
    getDoc: () => view.state.doc.toString(),
    setDoc(text) {
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text }, selection: { anchor: 0 } })
    },
    setDiagnostics(diags) {
      const doc = view.state.doc
      const mine = diags.filter((d) => !opts.fileName || d.file === opts.fileName)
      const cm: CmDiagnostic[] = mine.map((d) => {
        const { from, to } = toRange(doc, d)
        return {
          from,
          to,
          severity: d.severity === 'note' ? 'info' : d.severity,
          message: d.code ? `${d.code}: ${d.message}` : d.message,
          renderMessage: (v) => renderDiagnostic(d, opts.docsUrl, v, v.state.doc),
        }
      })
      const sec = mine.flatMap((d) => (d.secondary ?? []).filter((s) => !opts.fileName || s.file === opts.fileName).map((s) => toRange(doc, s)))
      view.dispatch(setDiagnostics(view.state, cm), { effects: setSecondary.of(sec) })
    },
    reveal(line, col) {
      const pos = offset(view.state.doc, line, col)
      view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) })
      view.focus()
    },
    focus: () => view.focus(),
    destroy: () => view.destroy(),
  }
}
