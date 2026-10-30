/**
 * The rich output protocol of Mini programs (docs/display.md): a program prints
 *
 *     ESC ] 5379 ; mini-display ; <mime> ; <base64 of the UTF-8 data> BEL
 *
 * on stdout (`mini.display` in the standard library does it; ST = `ESC \` also terminates). This file splits a stdout stream
 * into text and display bundles, across arbitrary chunk boundaries, and decides how a bundle may be shown.
 * Pure functions and classes without DOM access (the SVG sanitizer is in `sanitize.ts`), so they run under `node --test`.
 * Only erasable TypeScript syntax is used here.
 */

export const DISPLAY_PREFIX = '\u001b]5379;mini-display;'
const BEL = '\u0007'
const ST = '\u001b\\'
/** A bundle larger than this (characters of base64) is not buffered any further: it is shown as text. */
export const MAX_BUNDLE = 8 * 1024 * 1024

/** What the host can render; everything else is shown as a note. */
export const DISPLAY_MIMES = ['image/svg+xml', 'text/html', 'text/markdown', 'application/vnd.mini.table+json', 'text/plain', 'application/vnd.mini.digit-classifier+json'] as const

export interface DisplayBundle {
  mime: string
  data: string
}

export type Segment = { type: 'out' | 'err'; text: string } | ({ type: 'display' } & DisplayBundle)

export type Piece = { text: string } | { bundle: DisplayBundle }

/** The text a display bundle stands for where only text is compared (the expected output of a cell, CI). */
export const placeholder = (mime: string) => `[display: ${mime}]`

/** A placeholder on a line of its own, after the text `before` (the bundle itself has no newline). */
export const placeholderLine = (before: string, mime: string) => (before && !before.endsWith('\n') ? '\n' : '') + placeholder(mime) + '\n'

function decodeBase64(b64: string): string | null {
  try {
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}

/** Splits stdout into text and bundles. Feed it the chunks as they arrive; call `flush` at the end of the stream. */
export class DisplayParser {
  private buf = ''

  feed(chunk: string): Piece[] {
    this.buf += chunk
    return this.drain(false)
  }

  flush(): Piece[] {
    return this.drain(true)
  }

  private drain(end: boolean): Piece[] {
    const out: Piece[] = []
    const text = (t: string) => {
      if (t) out.push({ text: t })
    }
    for (;;) {
      const esc = this.buf.indexOf('\u001b')
      if (esc < 0) {
        text(this.buf)
        this.buf = ''
        return out
      }
      text(this.buf.slice(0, esc))
      this.buf = this.buf.slice(esc)
      const rest = this.buf
      if (rest.length < DISPLAY_PREFIX.length && DISPLAY_PREFIX.startsWith(rest)) {
        // maybe the start of a bundle: wait for more, unless the stream ended
        if (end) {
          text(rest)
          this.buf = ''
        }
        return out
      }
      if (!rest.startsWith(DISPLAY_PREFIX)) {
        // some other escape sequence (colours, ...): plain text, keep looking after this ESC
        text('\u001b')
        this.buf = rest.slice(1)
        continue
      }
      const bel = rest.indexOf(BEL, DISPLAY_PREFIX.length)
      const st = rest.indexOf(ST, DISPLAY_PREFIX.length)
      const stop = bel >= 0 && (st < 0 || bel < st) ? bel : st
      const stopLen = stop === bel ? BEL.length : ST.length
      if (stop < 0) {
        if (end || rest.length > MAX_BUNDLE) {
          text(rest)
          this.buf = ''
        }
        return out
      }
      const body = rest.slice(DISPLAY_PREFIX.length, stop)
      this.buf = rest.slice(stop + stopLen)
      const semi = body.indexOf(';')
      const mime = semi < 0 ? '' : body.slice(0, semi).trim().toLowerCase()
      const data = semi < 0 ? null : decodeBase64(body.slice(semi + 1).trim())
      if (!mime || data === null) text(placeholder('invalid'))
      else out.push({ bundle: { mime, data } })
    }
  }
}

/** Collects the stdout / stderr of a run into segments, with display bundles as their own segments. */
export class OutputSink {
  private parser = new DisplayParser()
  /** The stdout so far as text, display bundles replaced by `placeholder(mime)` (compared with documented output). */
  stdout = ''

  /** `segments` is mutated in place (pass the reactive array that the component renders). */
  private segments: Segment[]

  constructor(segments: Segment[]) {
    this.segments = segments
  }

  private text(type: 'out' | 'err', text: string) {
    const last = this.segments[this.segments.length - 1]
    if (last && last.type === type) last.text += text
    else this.segments.push({ type, text })
  }

  private pieces(ps: Piece[]) {
    for (const p of ps) {
      if ('text' in p) {
        this.stdout += p.text
        this.text('out', p.text)
      } else {
        this.stdout += placeholderLine(this.stdout, p.bundle.mime)
        this.segments.push({ type: 'display', mime: p.bundle.mime, data: p.bundle.data })
      }
    }
  }

  push(type: 'out' | 'err', text: string) {
    if (type === 'err') this.text('err', text)
    else this.pieces(this.parser.feed(text))
  }

  /** Call when the run is over: a half received bundle becomes text. */
  end() {
    this.pieces(this.parser.flush())
  }
}

/** The text of a complete stdout with every bundle replaced by its placeholder, plus the bundles. For tests and CI. */
export function splitOutput(raw: string): { text: string; bundles: DisplayBundle[] } {
  const p = new DisplayParser()
  const pieces = [...p.feed(raw), ...p.flush()]
  let text = ''
  const bundles: DisplayBundle[] = []
  for (const piece of pieces) {
    if ('text' in piece) text += piece.text
    else {
      text += placeholderLine(text, piece.bundle.mime)
      bundles.push(piece.bundle)
    }
  }
  return { text, bundles }
}

export interface TableData {
  columns: string[]
  rows: string[][]
}

/** Parses `application/vnd.mini.table+json`; null when it is not `{"columns": [...], "rows": [[...]]}`. */
export function parseTable(json: string): TableData | null {
  try {
    const v = JSON.parse(json)
    if (!v || !Array.isArray(v.columns) || !Array.isArray(v.rows)) return null
    if (!v.rows.every((r: unknown) => Array.isArray(r))) return null
    const str = (x: unknown) => (typeof x === 'string' ? x : JSON.stringify(x))
    return { columns: v.columns.map(str), rows: v.rows.map((r: unknown[]) => r.map(str)) }
  } catch {
    return null
  }
}
