/**
 * The contract between the playground / notebook front end and the in-browser Mini toolchain.
 * See README.md in this directory for the semantics and the worker message protocol.
 * Only erasable TypeScript syntax is used here so the files also run under `node --test`.
 */

export type Severity = 'error' | 'warning' | 'note'

export interface SourceFile {
  /** Path as the compiler sees it, e.g. `main.kt`. */
  name: string
  text: string
}

/** A source range. Lines and columns are 1-based; `endLine`/`endCol` are exclusive. Columns count UTF-16 code units. */
export interface Span {
  file: string
  line: number
  col: number
  endLine: number
  endCol: number
}

export interface SecondarySpan extends Span {
  /** What this location means, e.g. "the arena is closed here". */
  label: string
}

export interface Diagnostic extends Span {
  /** Stable code such as `M0102`. Absent for plain syntax/type errors that have no M-code. */
  code?: string
  severity: Severity
  message: string
  secondary?: SecondarySpan[]
  /** Suggestion text, shown as "help:". */
  help?: string
}

export type LoadStage = 'check' | 'full'

export interface LoadProgress {
  /** Which download or initialization step is running, e.g. `minic.wasm`, `clang`, `stdlib`. */
  stage: string
  /** Human-readable label for the UI. */
  label: string
  loadedBytes: number
  /** 0 when unknown. */
  totalBytes: number
  /** 0..1 over the whole requested load level, when the toolchain can tell. */
  fraction?: number
}

export interface ToolchainInfo {
  version: string
  /** Highest level that is loaded now. `check` means diagnostics work, compile does not yet. */
  loaded: LoadStage
  capabilities: { check: boolean; compile: boolean; mir: boolean; llvmIr: boolean }
}

export interface CompileOptions {
  emitMir?: boolean
  emitLlvmIr?: boolean
  optLevel?: '0' | '1' | '2' | 's'
}

export interface CompileResult {
  /** Present when compilation succeeded (no error diagnostics). A WASI command module exporting `_start`. */
  wasm?: Uint8Array
  diagnostics: Diagnostic[]
  mir?: string
  llvmIr?: string
  /** Milliseconds per phase. Well-known keys: minic, clang, link, total. Others are shown as is. */
  timings: Record<string, number>
}

export interface RunOptions {
  stdin?: string | Uint8Array
  args?: string[]
  env?: Record<string, string>
  onStdout: (text: string) => void
  onStderr: (text: string) => void
  /** The run is killed (worker terminated) after this many milliseconds. */
  timeoutMs: number
  /** Total stdout+stderr bytes after which the run is killed. Default 4 MiB. */
  maxOutputBytes?: number
  /** Aborting kills the run; the result has exitCode 130. */
  signal?: AbortSignal
}

export interface RunResult {
  /** 124 on timeout, 130 when aborted, 134 on a wasm trap, 153 when the output limit was exceeded, otherwise the program's exit status. */
  exitCode: number
  durationMs: number
  timedOut?: boolean
  outputLimitExceeded?: boolean
  /** Set when the module trapped or could not be instantiated. */
  error?: string
}

export interface MiniToolchain {
  /**
   * Downloads and initializes the toolchain. Level `check` (minic.wasm only, a few MB) is much smaller than
   * `full` (adds clang, wasm-ld, the runtime library). Idempotent; a later `full` upgrades an earlier `check`.
   */
  load(onProgress: (p: LoadProgress) => void, level?: LoadStage): Promise<ToolchainInfo>
  /** Parse and run the memory checker. Needs level `check`. */
  check(files: SourceFile[]): Promise<Diagnostic[]>
  /** Needs level `full`. */
  compile(files: SourceFile[], opts?: CompileOptions): Promise<CompileResult>
  /** Runs a compiled module. Needs no toolchain; the site provides it (WASI shim in a killable worker). */
  run(wasm: Uint8Array, opts: RunOptions): Promise<RunResult>
}

/** Thrown by `load` when the site ships no toolchain (no manifest at `<base>toolchain/manifest.json`). */
export class ToolchainUnavailableError extends Error {
  constructor(message = 'The Mini toolchain is not part of this build of the site.') {
    super(message)
    this.name = 'ToolchainUnavailableError'
  }
}

/** Contents of `<base>toolchain/manifest.json`, written by the toolchain build. */
export interface ToolchainManifest {
  /** Optional build-time compiled examples, matched against exact source + options + toolchain version. */
  programs?: { key: string; path: string; bytes: number; diagnostics: Diagnostic[] }[]
  version: string
  /** Module worker script, relative to the manifest. */
  worker: string
  /** Files with sizes, for progress and the service worker cache. Paths relative to the manifest. */
  files: { path: string; bytes: number; stage: LoadStage }[]
  capabilities: { check: boolean; compile: boolean; mir: boolean; llvmIr: boolean }
}

// ---- worker protocol (main thread <-> toolchain worker) ----

export type ToolchainRequest =
  | { id: number; type: 'load'; level: LoadStage; manifestUrl: string }
  | { id: number; type: 'check'; files: SourceFile[] }
  | { id: number; type: 'compile'; files: SourceFile[]; opts: CompileOptions }

export type ToolchainResponse =
  | { id: number; type: 'progress'; progress: LoadProgress }
  | { id: number; type: 'result'; value: unknown }
  | { id: number; type: 'error'; message: string }

// ---- worker protocol (main thread <-> runner worker, owned by the site) ----

export interface RunRequest {
  type: 'run'
  wasm: Uint8Array
  stdin?: string | Uint8Array
  args: string[]
  env: Record<string, string>
}

export type RunEvent =
  | { type: 'stdout'; data: string }
  | { type: 'stderr'; data: string }
  | { type: 'exit'; exitCode: number; durationMs: number; error?: string }
