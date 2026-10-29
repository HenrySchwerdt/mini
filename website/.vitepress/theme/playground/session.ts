/**
 * Page-level state shared by the playground and the notebook: whether the toolchain exists, what is loaded,
 * download progress, and the build-and-run pipeline. One toolchain instance per page load.
 */
import { ref, shallowRef } from 'vue'
import { WorkerToolchain, probeToolchain } from './toolchain.ts'
import {
  ToolchainUnavailableError,
  type CompileOptions,
  type Diagnostic,
  type LoadProgress,
  type LoadStage,
  type RunResult,
  type SourceFile,
  type ToolchainManifest,
} from './types.ts'

export type Availability = 'probing' | 'unavailable' | 'available'

export const availability = ref<Availability>('probing')
export const manifest = shallowRef<ToolchainManifest | undefined>()
export const loadedLevel = ref<LoadStage | null>(null)
export const progress = ref<LoadProgress | null>(null)
export const loadError = ref<string | null>(null)

let toolchain: WorkerToolchain | undefined
let siteBase = '/'
let probed: Promise<void> | undefined

export function setSiteBase(base: string) {
  siteBase = base
}

/** Fetches the (tiny) manifest once. Downloads nothing else. */
export function probe(): Promise<void> {
  probed ??= (async () => {
    manifest.value = await probeToolchain(siteBase)
    availability.value = manifest.value ? 'available' : 'unavailable'
  })()
  return probed
}

/** Loads the toolchain up to `level`, reporting progress. Throws on failure and records `loadError`. */
export async function ensureToolchain(level: LoadStage): Promise<WorkerToolchain> {
  await probe()
  if (availability.value !== 'available') throw new ToolchainUnavailableError()
  toolchain ??= new WorkerToolchain(siteBase)
  if (loadedLevel.value === 'full' || loadedLevel.value === level) return toolchain
  loadError.value = null
  try {
    const info = await toolchain.load((p) => (progress.value = p), level)
    loadedLevel.value = info.loaded
    return toolchain
  } catch (e) {
    loadError.value = (e as Error).message
    throw e
  } finally {
    progress.value = null
  }
}

export function cancelToolchain() {
  toolchain?.cancel()
  loadedLevel.value = null
}

export interface BuildOutcome {
  /** Phase that stopped the pipeline, or `done`. */
  stage: 'load' | 'compile' | 'run' | 'done'
  diagnostics: Diagnostic[]
  mir?: string
  llvmIr?: string
  timings: Record<string, number>
  run?: RunResult
  /** Set when the pipeline failed for a reason that is not a diagnostic (load failure, internal error). */
  failure?: string
}

export interface RunHandlers {
  onStdout: (s: string) => void
  onStderr: (s: string) => void
  stdin?: string
  timeoutMs: number
  signal?: AbortSignal
  compile?: CompileOptions
}

const hasErrors = (d: Diagnostic[]) => d.some((x) => x.severity === 'error')

/** Compile, then run. The toolchain is loaded on demand. */
export async function buildAndRun(files: SourceFile[], h: RunHandlers): Promise<BuildOutcome> {
  let tc: WorkerToolchain
  try {
    tc = await ensureToolchain('full')
  } catch (e) {
    return { stage: 'load', diagnostics: [], timings: {}, failure: (e as Error).message }
  }
  let res
  try {
    res = await tc.compile(files, h.compile)
  } catch (e) {
    return { stage: 'compile', diagnostics: [], timings: {}, failure: `The compiler failed: ${(e as Error).message}` }
  }
  const base = { diagnostics: res.diagnostics, mir: res.mir, llvmIr: res.llvmIr, timings: res.timings }
  if (!res.wasm || hasErrors(res.diagnostics)) return { stage: 'compile', ...base }
  const run = await tc.run(res.wasm, { stdin: h.stdin, onStdout: h.onStdout, onStderr: h.onStderr, timeoutMs: h.timeoutMs, signal: h.signal })
  return { stage: 'done', ...base, run }
}

/** Runs only the checker (needs just the small first stage of the toolchain). */
export async function checkOnly(files: SourceFile[]): Promise<{ diagnostics: Diagnostic[]; failure?: string }> {
  let tc: WorkerToolchain
  try {
    tc = await ensureToolchain('check')
  } catch (e) {
    return { diagnostics: [], failure: (e as Error).message }
  }
  try {
    return { diagnostics: await tc.check(files) }
  } catch (e) {
    return { diagnostics: [], failure: `The checker failed: ${(e as Error).message}` }
  }
}
