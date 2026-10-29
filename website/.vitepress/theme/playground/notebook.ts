/**
 * Notebook model, the `.minb` file format, and the cell-to-program assembly.
 *
 * Semantics (also documented on the notebook page and in README.md):
 *  - Top-level declarations of all cells up to and including the one being run accumulate: imports, file
 *    annotations, `fun`, `class`, `interface`, `object`, `enum`, `typealias`, and top-level `val`/`var`.
 *  - The remaining top-level statements of the cell being run become the body of `fun main()`.
 *  - Each run is one separate program (compile + run): state does not survive from one run to the next.
 *    Top-level `val`/`var` are file-level properties and are initialized again on every run.
 *  - A `fun main` declared in an earlier cell is not part of later cells' programs.
 */
import type { Diagnostic, SecondarySpan, SourceFile } from './types.ts'

export type CellType = 'markdown' | 'code'

export interface Cell {
  id: string
  type: CellType
  source: string
}

export interface Notebook {
  title: string
  cells: Cell[]
}

export const MINB_FORMAT = 'minb'
export const MINB_VERSION = 1

let idCounter = 0
export function newCellId(): string {
  return `c${Date.now().toString(36)}${(idCounter++).toString(36)}${Math.random().toString(36).slice(2, 6)}`
}

export function newCell(type: CellType, source = ''): Cell {
  return { id: newCellId(), type, source }
}

// ---- .minb ----

export function serializeNotebook(nb: Notebook): string {
  return JSON.stringify(
    { format: MINB_FORMAT, version: MINB_VERSION, title: nb.title, cells: nb.cells.map((c) => ({ type: c.type, source: c.source })) },
    null,
    2,
  ) + '\n'
}

/** Parses and validates a `.minb` document. Throws an Error with a readable message. */
export function parseNotebook(text: string): Notebook {
  let raw: any
  try {
    raw = JSON.parse(text)
  } catch {
    throw new Error('This is not a .minb file (invalid JSON).')
  }
  if (!raw || raw.format !== MINB_FORMAT) throw new Error('This is not a .minb file (missing "format": "minb").')
  if (typeof raw.version !== 'number' || raw.version > MINB_VERSION) {
    throw new Error(`Unsupported .minb version ${raw.version}; this page reads version ${MINB_VERSION}.`)
  }
  if (!Array.isArray(raw.cells)) throw new Error('The .minb file has no "cells" array.')
  const cells: Cell[] = raw.cells.map((c: any, i: number) => {
    if (!c || (c.type !== 'markdown' && c.type !== 'code') || typeof c.source !== 'string') {
      throw new Error(`Cell ${i + 1} is malformed (needs "type": "markdown" | "code" and a string "source").`)
    }
    return { id: newCellId(), type: c.type as CellType, source: c.source }
  })
  return { title: typeof raw.title === 'string' ? raw.title : 'Untitled notebook', cells }
}

// ---- editing operations (pure) ----

export function insertCell(nb: Notebook, index: number, cell: Cell): Notebook {
  const cells = nb.cells.slice()
  cells.splice(Math.max(0, Math.min(index, cells.length)), 0, cell)
  return { ...nb, cells }
}

export function deleteCell(nb: Notebook, index: number): Notebook {
  if (index < 0 || index >= nb.cells.length) return nb
  const cells = nb.cells.slice()
  cells.splice(index, 1)
  return { ...nb, cells }
}

export function moveCell(nb: Notebook, index: number, delta: -1 | 1): Notebook {
  const to = index + delta
  if (index < 0 || index >= nb.cells.length || to < 0 || to >= nb.cells.length) return nb
  const cells = nb.cells.slice()
  const [c] = cells.splice(index, 1)
  cells.splice(to, 0, c)
  return { ...nb, cells }
}

// ---- splitting a cell into declarations and statements ----

export type ChunkKind = 'fileAnnotation' | 'import' | 'decl' | 'stmt'

export interface Chunk {
  kind: ChunkKind
  /** 1-based line of the first line of the chunk within the cell. */
  startLine: number
  lines: string[]
  /** `fun main` declaration. */
  isMain?: boolean
}

interface LineInfo {
  text: string
  /** Nothing but whitespace and comments. */
  blank: boolean
  /** Bracket depth, or an open comment/raw string, at the end of the line. */
  open: boolean
  /** Last significant character (comments and whitespace ignored). */
  last: string
  /** Last two significant characters. */
  last2: string
  /** The code of the line with comments and string contents blanked out (same length). */
  code: string
}

/** Single pass over the cell that tracks strings, comments and brackets. */
function scanLines(source: string): LineInfo[] {
  const lines = source.split('\n')
  const out: LineInfo[] = []
  let depth = 0
  let blockComment = 0
  let rawString = false
  // open `${ }` templates: the brace depth they started at and which string kind to resume afterwards
  const templateStack: { depth: number; resume: 'raw' | 'str' }[] = []
  for (const text of lines) {
    let code = ''
    let last = ''
    let last2 = ''
    let sig = false
    const sigPush = (ch: string) => {
      last2 = (last + ch).slice(-2)
      last = ch
      sig = true
    }
    let inString = false // regular "..." string on this line
    let i = 0
    const n = text.length
    const blank = (len: number) => ' '.repeat(len)
    while (i < n) {
      const ch = text[i]
      const two = text.substr(i, 2)
      if (blockComment > 0) {
        if (two === '*/') (blockComment--, (code += '  '), (i += 2))
        else if (two === '/*') (blockComment++, (code += '  '), (i += 2))
        else (code += ' ', i++)
        continue
      }
      if (rawString || inString) {
        if (rawString && text.substr(i, 3) === '"""') {
          // a run of more than three quotes ends the string with the last three
          let j = i
          while (text[j + 3] === '"') j++
          code += blank(j - i + 3)
          i = j + 3
          rawString = false
          sigPush('"')
          continue
        }
        if (inString && ch === '\\') (code += '  ', (i += 2))
        else if (inString && ch === '"') (code += ' ', i++, (inString = false), sigPush('"'))
        else if (ch === '$' && text[i + 1] === '{') {
          // template expression: code again until the matching brace
          templateStack.push({ depth, resume: rawString ? 'raw' : 'str' })
          depth++
          code += '  '
          i += 2
          // code again until the matching brace
          rawString = false
          inString = false
        } else (code += ' ', i++)
        continue
      }
      if (two === '//') {
        code += blank(n - i)
        break
      }
      if (two === '/*') {
        blockComment++
        code += '  '
        i += 2
        continue
      }
      if (text.substr(i, 3) === '"""') {
        rawString = true
        code += '   '
        i += 3
        sigPush('"')
        continue
      }
      if (ch === '"') {
        inString = true
        code += ' '
        i++
        continue
      }
      if (ch === "'") {
        // char literal: '\'' or 'x'
        const m = /^'(\\.[^']*|[^'\\])'/.exec(text.slice(i))
        if (m) {
          code += blank(m[0].length)
          i += m[0].length
          sigPush("'")
          continue
        }
      }
      if (ch === '(' || ch === '[' || ch === '{') depth++
      if (ch === ')' || ch === ']' || ch === '}') {
        depth--
        if (ch === '}' && templateStack.length > 0 && templateStack[templateStack.length - 1].depth === depth) {
          const { resume } = templateStack.pop()!
          code += ' '
          i++
          if (resume === 'raw') rawString = true
          else inString = true
          continue
        }
      }
      code += ch
      if (ch !== ' ' && ch !== '\t' && ch !== '\r') sigPush(ch)
      i++
    }
    out.push({ text, blank: !sig && blockComment === 0 && !rawString && !inString, open: depth > 0 || blockComment > 0 || rawString, last, last2, code })
  }
  return out
}

const MODIFIERS = new Set([
  'public', 'private', 'protected', 'internal', 'open', 'final', 'abstract', 'sealed', 'data', 'enum', 'annotation',
  'inline', 'noinline', 'crossinline', 'value', 'inner', 'override', 'const', 'lateinit', 'suspend', 'operator',
  'infix', 'tailrec', 'external', 'expect', 'actual', 'vararg', 'companion',
])

function firstWords(code: string): string[] {
  const words: string[] = []
  // strip annotations (with an optional argument list that has no nested parentheses on the first line)
  let rest = code.trim()
  for (;;) {
    const m = /^@(?!file:)[\w.]+(\([^)]*\))?\s*/.exec(rest)
    if (!m) break
    rest = rest.slice(m[0].length)
  }
  const re = /^([A-Za-z_]\w*)\s*/
  for (let k = 0; k < 8; k++) {
    const m = re.exec(rest)
    if (!m) break
    words.push(m[1])
    rest = rest.slice(m[0].length)
    if (!MODIFIERS.has(m[1])) {
      words.push(rest.slice(0, 1))
      break
    }
  }
  return words
}

function classify(code: string): { kind: ChunkKind; isMain?: boolean } {
  const trimmed = code.trim()
  if (/^@file:/.test(trimmed)) return { kind: 'fileAnnotation' }
  const w = firstWords(code)
  let idx = 0
  while (idx < w.length && MODIFIERS.has(w[idx])) idx++
  const kw = w[idx]
  const next = w[idx + 1] ?? ''
  switch (kw) {
    case 'import':
      return { kind: 'import' }
    case 'class':
    case 'interface':
    case 'typealias':
      return { kind: 'decl' }
    case 'object':
      return next === ':' || next === '{' ? { kind: 'stmt' } : { kind: 'decl' }
    case 'fun': {
      // `fun(x: Int) = ...` is an anonymous function expression, `fun <T> id(...)` and `fun String.f()` are declarations
      if (next === '(') return { kind: 'stmt' }
      const isMain = /^[^(]*\bfun\s+main\s*\(/.test(trimmed.replace(/^(@[\w.]+(\([^)]*\))?\s*)+/, ''))
      return { kind: 'decl', isMain }
    }
    case 'val':
    case 'var':
      // top-level destructuring is not allowed in Kotlin, so it is a statement
      return next === '(' ? { kind: 'stmt' } : { kind: 'decl' }
    default:
      return { kind: 'stmt' }
  }
}

const CONT_END = new Set(['=', ',', '(', '[', '+', '-', '*', '/', '%', '&', '|', '.'])
const CONT_START = /^(\.|\?\.|\?:|&&|\|\||:|=(?!=)|else\b|catch\b|finally\b|by\b|where\b|\{|->)/

/** Splits a cell into top-level chunks (declarations, imports, statements) with their line positions. */
export function splitCell(source: string): Chunk[] {
  const infos = scanLines(source)
  const chunks: Chunk[] = []
  let cur: { start: number; lines: LineInfo[] } | undefined
  let trivia: { start: number; lines: LineInfo[] } | undefined // comments/blank lines before the next chunk

  const finish = () => {
    if (!cur) return
    const code = cur.lines.map((l) => l.code).join('\n')
    const { kind, isMain } = classify(code)
    chunks.push({ kind, startLine: cur.start, lines: cur.lines.map((l) => l.text), isMain })
    cur = undefined
  }

  const continues = (c: { lines: LineInfo[] }, nextCode: string | undefined): boolean => {
    const lastInfo = c.lines[c.lines.length - 1]
    if (lastInfo.open) return true
    const joined = c.lines.map((l) => l.code).join('\n').trim()
    if (/^(@(?!file:)[\w.]+(\([^)]*\))?\s*)+$/.test(joined)) return true // annotations only
    const l2 = lastInfo.last2
    if (CONT_END.has(lastInfo.last) && l2 !== '++' && l2 !== '--' && l2 !== '.*') return true
    if (/\b(else|do)$/.test(lastInfo.code.trim())) return true
    if (c.lines.length === 1 && /^(if|while|for|when)\b/.test(joined) && lastInfo.last === ')') return true
    if (nextCode !== undefined && CONT_START.test(nextCode.trim())) return true
    return false
  }

  for (let i = 0; i < infos.length; i++) {
    const info = infos[i]
    if (!cur) {
      if (info.blank) {
        trivia ??= { start: i + 1, lines: [] }
        trivia.lines.push(info)
        continue
      }
      cur = trivia ? { start: trivia.start, lines: [...trivia.lines, info] } : { start: i + 1, lines: [info] }
      trivia = undefined
    } else {
      cur.lines.push(info)
    }
    // next non-blank line decides on continuation starts
    let j = i + 1
    while (j < infos.length && infos[j].blank && !infos[j].open) j++
    const nextCode = j < infos.length ? infos[j].code : undefined
    if (!continues(cur, nextCode)) finish()
  }
  finish()
  if (trivia) {
    // trailing comments: keep them with the statements so line numbers stay mapped
    chunks.push({ kind: 'stmt', startLine: trivia.start, lines: trivia.lines.map((l) => l.text) })
  }
  return chunks
}

// ---- assembling the program for one cell ----

export interface LineOrigin {
  /** Index into the notebook's cells, or -1 for lines generated by the assembler. */
  cell: number
  /** 1-based line within that cell (0 for generated lines). */
  line: number
}

export interface Assembled {
  file: SourceFile
  origins: LineOrigin[]
  /** True when the cell has no code at all. */
  empty: boolean
}

export const NOTEBOOK_FILE = 'notebook.kt'

export function assembleProgram(cells: Cell[], target: number): Assembled {
  const header: { origin: LineOrigin; text: string }[] = []
  const imports: { origin: LineOrigin; text: string }[] = []
  const decls: { origin: LineOrigin; text: string }[] = []
  const body: { origin: LineOrigin; text: string }[] = []
  const seenImports = new Set<string>()
  let ownMain = false
  let hasBody = false
  let any = false

  for (let ci = 0; ci <= target && ci < cells.length; ci++) {
    const cell = cells[ci]
    if (cell.type !== 'code') continue
    for (const chunk of splitCell(cell.source)) {
      const place = (dest: typeof body) =>
        chunk.lines.forEach((text, k) => dest.push({ origin: { cell: ci, line: chunk.startLine + k }, text }))
      const isTarget = ci === target
      if (chunk.kind === 'fileAnnotation') place(header)
      else if (chunk.kind === 'import') {
        const key = chunk.lines.join('\n').trim()
        if (!seenImports.has(key)) (seenImports.add(key), place(imports))
      } else if (chunk.kind === 'decl') {
        if (chunk.isMain && !isTarget) continue
        if (chunk.isMain) ownMain = true
        place(decls)
        decls.push({ origin: { cell: -1, line: 0 }, text: '' })
      } else if (isTarget) {
        place(body)
        if (chunk.lines.some((l) => l.trim() !== '' && !/^\s*\/\//.test(l))) hasBody = true
      }
      if (isTarget && chunk.lines.some((l) => l.trim() !== '')) any = true
    }
  }

  const gen: LineOrigin = { cell: -1, line: 0 }
  const lines: { origin: LineOrigin; text: string }[] = [...header, ...imports, ...decls]
  if (!ownMain || hasBody) {
    lines.push({ origin: { cell: target, line: 1 }, text: 'fun main() {' })
    lines.push(...body)
    lines.push({ origin: gen, text: '}' })
  }
  return {
    file: { name: NOTEBOOK_FILE, text: lines.map((l) => l.text).join('\n') + '\n' },
    origins: lines.map((l) => l.origin),
    empty: !any,
  }
}

export interface MappedDiagnostic extends Omit<Diagnostic, 'secondary'> {
  /** Index of the cell the diagnostic belongs to (the cell being run when it points at generated code). */
  cell: number
  secondary?: (SecondarySpan & { cell: number })[]
}

/** Maps diagnostics on the assembled file back to cell coordinates. */
export function mapDiagnostics(diags: Diagnostic[], asm: Assembled, target: number): MappedDiagnostic[] {
  const map = (line: number, endLine: number) => {
    const o = asm.origins[line - 1]
    const e = asm.origins[endLine - 1]
    if (!o || o.cell < 0) return { cell: target, line: 1, endLine: 1, ok: false }
    return { cell: o.cell, line: o.line, endLine: e && e.cell === o.cell ? e.line : o.line, ok: true }
  }
  return diags.map((d) => {
    const m = map(d.line, d.endLine)
    return {
      ...d,
      cell: m.cell,
      line: m.line,
      endLine: m.endLine,
      col: m.ok ? d.col : 1,
      endCol: m.ok ? d.endCol : 1,
      secondary: d.secondary?.map((s) => {
        const sm = map(s.line, s.endLine)
        return { ...s, cell: sm.cell, line: sm.line, endLine: sm.endLine }
      }),
    }
  })
}
