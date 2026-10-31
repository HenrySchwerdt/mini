/**
 * Page-level state shared by the playground and the notebook: whether the toolchain exists, what is loaded,
 * download progress, and the build-and-run pipeline. One toolchain instance per page load.
 */
import { ref, shallowRef } from 'vue'
import { WorkerToolchain, probeToolchain } from './toolchain.ts'
import { CompileCache } from './compile-cache.ts'
import { runInWorker } from './run-client.ts'
import {
  ToolchainUnavailableError,
  type CompileOptions,
  type CompileResult,
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

let loadChain: Promise<unknown> = Promise.resolve()

/**
 * Loads the toolchain up to `level`, reporting progress. Throws on failure and records `loadError`.
 * Calls are serialized (the preload, a Run click and the playground may all ask at once), and a level that is already loaded is free.
 */
export function ensureToolchain(level: LoadStage): Promise<WorkerToolchain> {
  const p = loadChain.then(() => loadNow(level))
  loadChain = p.catch(() => {})
  return p
}

async function loadNow(level: LoadStage): Promise<WorkerToolchain> {
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

const programCache = new CompileCache(typeof caches === 'undefined' ? undefined : caches)

let busy: Promise<unknown> = Promise.resolve()

/** The toolchain worker handles one request at a time: compile and check calls from different cells queue up here. */
export function exclusive<T>(fn: () => Promise<T>): Promise<T> {
  const p = busy.then(fn)
  busy = p.catch(() => {})
  return p
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
  const cancelled = (): BuildOutcome => ({ stage: 'done', diagnostics: [], timings: {}, run: { exitCode: 130, durationMs: 0 } })
  if (h.signal?.aborted) return cancelled()
  const options: CompileOptions = { ...h.compile, optLevel: h.compile?.optLevel ?? '1' }
  await probe()
  let key: string | undefined
  let res: CompileResult | undefined
  try {
    if (manifest.value) {
      key = await programCache.key(manifest.value.version, files, options)
      res = await programCache.get(key)
    }
  } catch { /* A missing hashing/storage API only disables caching. */ }
  if (h.signal?.aborted) return cancelled()
  if (res) res.timings = { cacheHit: 1 }
  if (!res) {
    let tc: WorkerToolchain
    try {
      tc = await ensureToolchain('full')
    } catch (e) {
      return { stage: 'load', diagnostics: [], timings: {}, failure: (e as Error).message }
    }
    if (h.signal?.aborted) return cancelled()
    try {
      res = await exclusive(async () => {
        if (h.signal?.aborted) return { diagnostics: [], timings: {} }
        const queued = key ? await programCache.get(key) : undefined
        if (queued) return { ...queued, timings: { cacheHit: 1, total: 0 } }
        const compiled = await tc.compile(files, options)
        if (key && !h.signal?.aborted) await programCache.put(key, compiled)
        return compiled
      })
    } catch (e) {
      return { stage: 'compile', diagnostics: [], timings: {}, failure: `The compiler failed: ${(e as Error).message}` }
    }
  }
  if (h.signal?.aborted) return cancelled()
  const base = { diagnostics: res.diagnostics, mir: res.mir, llvmIr: res.llvmIr, timings: res.timings }
  if (!res.wasm || hasErrors(res.diagnostics)) return { stage: 'compile', ...base }
  const run = await runInWorker(res.wasm, { stdin: h.stdin, onStdout: h.onStdout, onStderr: h.onStderr, timeoutMs: h.timeoutMs, signal: h.signal })
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
    return { diagnostics: await exclusive(() => tc.check(files)) }
  } catch (e) {
    return { diagnostics: [], failure: `The checker failed: ${(e as Error).message}` }
  }
}
