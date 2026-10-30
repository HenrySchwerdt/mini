/**
 * How a fenced Kotlin block of the docs becomes a runnable cell. Pure functions, shared by
 *  - the markdown-it plugin (`../mini-cells.ts`, build time),
 *  - the cell component (`MiniCell.vue`, browser), which assembles the program of a cell, and
 *  - `scripts/extract-snippets.mjs`, which writes exactly the programs the browser compiles to disk so CI can check them.
 *
 * Fence syntax (after the language):
 *   ```kotlin                 a runnable cell if it declares `fun main`, otherwise plain highlighted code
 *   ```kotlin run             a fragment that is runnable too: declarations plus statements, wrapped in `main`
 *   ```kotlin norun           never runnable (C interop, fragments that need a context, pseudo code)
 *   ```kotlin check           a Check cell: only the memory checker runs, expected: no diagnostics
 *   ```kotlin check=M0102     ... expected: error M0102   (`check=warn:M0202`: warning, `check=ok`: none)
 *   ```kotlin fresh           (notebook pages) do not share declarations with the cells above
 *   ```kotlin chain           share declarations with the cells above even on a page without `notebook: true`
 *   ```kotlin exit=1          the program is expected to end with this exit status (an uncaught exception)
 *   ```kotlin exercise        an exercise: the next ```output block is the goal, not a checked expectation. With `check=CODE` it is a check
 *                             exercise: the starter is expected to report CODE (verified in CI), the goal is a program without diagnostics
 * A ```output fence directly after a runnable cell is its expected output: shown as a hint, compared in the browser
 * and verified in CI.
 * `<<< @/samples/x.kt{kotlin}` includes follow the files next to the sample instead: `x.out` makes a Run cell,
 * `x.check` a Check cell, `x.norun` or neither makes plain code.
 */
import { assembleProgram, mapDiagnostics, type Assembled, type Cell, type MappedDiagnostic } from './playground/notebook.ts'
import type { Diagnostic, SourceFile } from './playground/types.ts'

export type CellKind = 'run' | 'check'

export interface CheckExpectation {
  kind: 'ok' | 'error' | 'warning'
  code?: string
}

/** What the markdown plugin knows about one runnable block. Serializable: it is passed to the component as a prop. */
export interface CellSpec {
  /** Position among the runnable cells of the page. */
  index: number
  kind: CellKind
  /** Shares declarations with the chain cells above it (notebook semantics). */
  chain: boolean
  exercise: boolean
  source: string
  /** Expected stdout of a run cell (a documented output, or the goal of an exercise). */
  expected?: string
  /** Expectation of a check cell. */
  expect?: CheckExpectation
  /** Exit status the program is expected to end with (default 0), e.g. a program that ends with an uncaught exception: `exit=1`. */
  exit?: number
  /** Sample file (relative to `website/samples`) the code was included from. */
  from?: string
}

export interface FenceInfo {
  lang: string
  flags: Map<string, string | true>
}

/** `kotlin run check=M0102` -> lang `kotlin`, flags {run, check: M0102}. `{...}` and `[...]` suffixes of the language are dropped. */
export function parseFenceInfo(info: string): FenceInfo {
  const parts = info.trim().split(/\s+/).filter(Boolean)
  const lang = (parts[0] ?? '').replace(/[{[].*$/, '').toLowerCase()
  const flags = new Map<string, string | true>()
  for (const p of parts.slice(1)) {
    const eq = p.indexOf('=')
    if (eq < 0) flags.set(p, true)
    else flags.set(p.slice(0, eq), p.slice(eq + 1))
  }
  return { lang, flags }
}

export const isKotlin = (lang: string) => lang === 'kotlin' || lang === 'kt'

/** True when the code declares a top-level `fun main` (a complete program). */
export function hasMain(source: string): boolean {
  return /^[ \t]*(?:@[\w.]+(?:\([^)]*\))?[ \t]+)*(?:(?:public|private|internal)[ \t]+)?fun[ \t]+main[ \t]*\(/m.test(source)
}

/** `ok`, `M0102` (error), `warn:M0202` (warning). */
export function parseCheckFlag(v: string | true | undefined): CheckExpectation {
  if (v === undefined || v === true || v === 'ok') return { kind: 'ok' }
  if (v.startsWith('warn:')) return { kind: 'warning', code: v.slice(5) }
  return { kind: 'error', code: v }
}

/** The content of a `.check` file next to a sample: `ok`, `error M0102` or `warning M0202`. */
export function parseCheckFile(text: string): CheckExpectation {
  const [kind, code] = text.trim().split(/\s+/)
  if (kind === 'error' || kind === 'warning') return { kind, code }
  return { kind: 'ok' }
}

export function describeExpectation(e: CheckExpectation): string {
  return e.kind === 'ok' ? 'no diagnostics' : `${e.kind} ${e.code}`
}

export interface DecideInput {
  info: string
  source: string
  /** The page has `notebook: true`. */
  notebookPage: boolean
  /** Set for `<<<` includes: the sample path (relative to samples/) and what is next to it. */
  sample?: { path: string; out?: string; check?: string; norun: boolean }
  /** Content of a directly following ```output fence. */
  output?: string
}

/** Decides whether a fence is a cell, and which. `index` is filled in by the caller. */
export function decideCell(i: DecideInput): Omit<CellSpec, 'index'> | undefined {
  const { lang, flags } = parseFenceInfo(i.info)
  if (!isKotlin(lang) || flags.has('norun')) return undefined
  const base = { source: i.source, exercise: flags.has('exercise') }
  if (i.sample) {
    if (i.sample.norun) return undefined
    if (i.sample.check !== undefined) return { ...base, kind: 'check', chain: false, expect: parseCheckFile(i.sample.check), from: i.sample.path }
    if (i.sample.out === undefined && !flags.has('run')) return undefined
    const chain = wantsChain(flags, i.source, i.notebookPage)
    return { ...base, kind: 'run', chain, expected: i.sample.out, from: i.sample.path }
  }
  if (flags.has('check')) return { ...base, kind: 'check', chain: false, expect: parseCheckFlag(flags.get('check')) }
  // On a notebook page every fragment is a cell (mark pseudo code `norun`); elsewhere only complete programs and `run` fragments.
  if (!flags.has('run') && !hasMain(i.source) && !i.notebookPage) return undefined
  return { ...base, kind: 'run', chain: wantsChain(flags, i.source, i.notebookPage), expected: i.output, ...exitOf(flags) }
}

function exitOf(flags: Map<string, string | true>): { exit?: number } {
  const v = flags.get('exit')
  return typeof v === 'string' && /^\d+$/.test(v) ? { exit: Number(v) } : {}
}

function wantsChain(flags: Map<string, string | true>, source: string, notebookPage: boolean): boolean {
  if (flags.has('fresh')) return false
  if (flags.has('chain')) return true
  // On a notebook page a complete program (it has its own `main`) stands alone; fragments share declarations.
  return notebookPage && !hasMain(source)
}

// ---- assembling the program of a cell ----

export interface CellProgram {
  file: SourceFile
  /** Index of the cell within the assembled program's cell list (the `cell` of mapped diagnostics that belong to this cell). */
  target: number
  /** Maps diagnostics of the compiled file to lines of the cell's own source. Diagnostics elsewhere (earlier cells) are kept with `cell` set. */
  map: (diags: Diagnostic[]) => MappedDiagnostic[]
}

const FILE = 'main.kt'

/**
 * The program that runs cell `k` of `cells` (`sources[i]` is the current, possibly edited, source of `cells[i]`).
 * Chain cells see the declarations of the chain cells above them (the notebook rules); a cell with `fun main` that does not chain
 * is compiled as it is; any other cell is wrapped: its statements become `fun main`.
 */
export function programFor(cells: Pick<CellSpec, 'chain' | 'kind'>[], sources: string[], k: number): CellProgram {
  const me = cells[k]
  if (me.chain) {
    const list: Cell[] = []
    let target = 0
    for (let i = 0; i <= k; i++) {
      if (!cells[i].chain || cells[i].kind !== 'run') continue
      if (i === k) target = list.length
      list.push({ id: `c${i}`, type: 'code', source: sources[i] })
    }
    return fromAssembled(assembleProgram(list, target), target)
  }
  const src = sources[k]
  if (hasMain(src)) {
    const lines = src.split('\n').length
    const origins = Array.from({ length: lines }, (_, i) => ({ cell: 0, line: i + 1 }))
    return fromAssembled({ file: { name: FILE, text: src }, origins, empty: false }, 0)
  }
  return fromAssembled(assembleProgram([{ id: 'c0', type: 'code', source: src }], 0), 0)
}

function fromAssembled(asm: Assembled, target: number): CellProgram {
  const file = { name: FILE, text: asm.file.text }
  return { file, target, map: (diags) => mapDiagnostics(diags, asm, target) }
}

/** Compares output the way the docs show it: trailing whitespace of the whole text and of each line does not matter. */
export function sameOutput(actual: string, expected: string): boolean {
  const norm = (s: string) => s.replace(/\r\n/g, '\n').split('\n').map((l) => l.replace(/\s+$/, '')).join('\n').replace(/\n+$/, '')
  return norm(actual) === norm(expected)
}

/** Does the diagnostic list satisfy a check expectation? (Same rule as `check-samples.sh`: exactly this code, nothing else.) */
export function meetsExpectation(diags: Pick<Diagnostic, 'code' | 'severity'>[], e: CheckExpectation): boolean {
  const coded = [...new Set(diags.filter((d) => d.code).map((d) => d.code!))]
  const errors = diags.some((d) => d.severity === 'error')
  if (e.kind === 'ok') return coded.length === 0 && !errors
  if (e.kind === 'error') return errors && coded.length === 1 && coded[0] === e.code
  return !errors && coded.length === 1 && coded[0] === e.code
}
