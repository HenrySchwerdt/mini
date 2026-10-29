import { runInWorker } from './run-client.ts'
import {
  ToolchainUnavailableError,
  type CompileOptions,
  type CompileResult,
  type Diagnostic,
  type LoadProgress,
  type LoadStage,
  type MiniToolchain,
  type RunOptions,
  type RunResult,
  type SourceFile,
  type ToolchainInfo,
  type ToolchainManifest,
  type ToolchainRequest,
  type ToolchainResponse,
} from './types.ts'

type RequestBody = ToolchainRequest extends infer R ? (R extends unknown ? Omit<R, 'id'> : never) : never

/** Where the toolchain build drops its files. Relative to the site base, so it works under /mini/. */
export function toolchainBase(siteBase: string): string {
  return `${siteBase.replace(/\/?$/, '/')}toolchain/`
}

/**
 * Fetches the manifest. Returns undefined when the site ships no toolchain. A dev server answers unknown
 * URLs with the HTML shell, so the body is parsed and validated rather than trusting the status code.
 */
export async function probeToolchain(siteBase: string): Promise<ToolchainManifest | undefined> {
  try {
    const res = await fetch(`${toolchainBase(siteBase)}manifest.json`, { cache: 'no-cache' })
    if (!res.ok) return undefined
    const m = (await res.json()) as Partial<ToolchainManifest>
    if (typeof m.version !== 'string' || typeof m.worker !== 'string' || !Array.isArray(m.files) || !m.capabilities) return undefined
    return m as ToolchainManifest
  } catch {
    return undefined
  }
}

/** Registers the cache-first service worker for the toolchain files (see README, "Caching"). Best effort. */
function registerServiceWorker(siteBase: string, version: string) {
  try {
    if (!('serviceWorker' in navigator) || location.hostname === 'localhost') return
    const base = siteBase.replace(/\/?$/, '/')
    void navigator.serviceWorker
      .register(`${base}mini-sw.js?v=${encodeURIComponent(version)}`, { scope: base })
      .catch(() => {})
  } catch {
    /* ignore */
  }
}

/**
 * The main-thread side of the toolchain: check/compile go to the toolchain worker built by the compiler team,
 * run goes to the site's own runner worker.
 */
export class WorkerToolchain implements MiniToolchain {
  private worker: Worker | undefined
  private nextId = 1
  private readonly waiting = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; onProgress?: (p: LoadProgress) => void }>()
  private info: ToolchainInfo | undefined
  private manifest: ToolchainManifest | undefined
  private loading: { level: LoadStage; promise: Promise<ToolchainInfo> } | undefined

  private readonly siteBase: string

  constructor(siteBase: string) {
    this.siteBase = siteBase
  }

  /** Aborts whatever the toolchain worker is doing by killing it. The next `load` starts a fresh one. */
  cancel() {
    this.fail('Cancelled.')
  }

  get loadedLevel(): LoadStage | undefined {
    return this.info?.loaded
  }

  private send<T>(req: RequestBody, onProgress?: (p: LoadProgress) => void): Promise<T> {
    const worker = this.worker!
    const id = this.nextId++
    return new Promise<T>((resolve, reject) => {
      this.waiting.set(id, { resolve, reject, onProgress })
      worker.postMessage({ ...req, id })
    })
  }

  private onMessage = (ev: MessageEvent<ToolchainResponse>) => {
    const m = ev.data
    const w = this.waiting.get(m.id)
    if (!w) return
    if (m.type === 'progress') return w.onProgress?.(m.progress)
    this.waiting.delete(m.id)
    if (m.type === 'result') w.resolve(m.value)
    else w.reject(new Error(m.message))
  }

  private fail(message: string) {
    for (const w of this.waiting.values()) w.reject(new Error(message))
    this.waiting.clear()
    this.worker?.terminate()
    this.worker = undefined
    this.info = undefined
    this.loading = undefined
  }

  async load(onProgress: (p: LoadProgress) => void, level: LoadStage = 'full'): Promise<ToolchainInfo> {
    if (this.info && (this.info.loaded === 'full' || level === 'check')) return this.info
    if (this.loading && (this.loading.level === 'full' || level === 'check')) return this.loading.promise
    const run = async () => {
      if (!this.manifest) {
        this.manifest = await probeToolchain(this.siteBase)
        if (!this.manifest) throw new ToolchainUnavailableError()
        registerServiceWorker(this.siteBase, this.manifest.version)
      }
      if (!this.worker) {
        const base = toolchainBase(this.siteBase)
        this.worker = new Worker(new URL(this.manifest.worker, location.origin + base), { type: 'module' })
        this.worker.onmessage = this.onMessage
        this.worker.onerror = (e) => this.fail(e.message || 'the toolchain worker crashed')
      }
      const manifestUrl = new URL(`${toolchainBase(this.siteBase)}manifest.json`, location.origin).href
      this.info = await this.send<ToolchainInfo>({ type: 'load', level, manifestUrl }, onProgress)
      return this.info
    }
    const promise = run()
    this.loading = { level, promise }
    try {
      return await promise
    } finally {
      if (this.loading?.promise === promise) this.loading = undefined
    }
  }

  async check(files: SourceFile[]): Promise<Diagnostic[]> {
    if (!this.info) throw new Error('The toolchain is not loaded.')
    return this.send<Diagnostic[]>({ type: 'check', files })
  }

  async compile(files: SourceFile[], opts: CompileOptions = {}): Promise<CompileResult> {
    if (this.info?.loaded !== 'full') throw new Error('The full toolchain is not loaded.')
    return this.send<CompileResult>({ type: 'compile', files, opts })
  }

  run(wasm: Uint8Array, opts: RunOptions): Promise<RunResult> {
    return runInWorker(wasm, opts)
  }
}
