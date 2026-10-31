import type { CompileOptions, CompileResult, SourceFile } from './types.ts'

const CACHE_NAME = 'mini-compiled-programs-v1'
const MAX_ENTRIES = 8
const MAX_BYTES = 8 * 1024 * 1024

/** Bounded optional cache. The toolchain version covers compiler, runtime, stdlib and library sources. */
export class CompileCache {
  private memory = new Map<string, CompileResult>()
  private storage: CacheStorage | undefined

  constructor(storage?: CacheStorage) { this.storage = storage }

  async key(version: string, files: SourceFile[], options: CompileOptions): Promise<string> {
    const input = JSON.stringify([version, files, {
      optLevel: options.optLevel ?? '1', emitMir: !!options.emitMir, emitLlvmIr: !!options.emitLlvmIr,
    }])
    const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
    return 'https://mini.invalid/compiled/' + Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('')
  }

  private remember(key: string, result: CompileResult) {
    this.memory.delete(key)
    this.memory.set(key, structuredClone(result))
    while (this.memory.size > MAX_ENTRIES) this.memory.delete(this.memory.keys().next().value!)
  }

  async get(key: string): Promise<CompileResult | undefined> {
    const memory = this.memory.get(key)
    if (memory) { this.remember(key, memory); return structuredClone(memory) }
    try {
      const response = await (await this.storage?.open(CACHE_NAME))?.match(key)
      if (!response) return undefined
      const bytes = new Uint8Array(await response.arrayBuffer())
      if (bytes.length > MAX_BYTES) return undefined
      const newline = bytes.indexOf(10)
      if (newline < 0) return undefined
      const metadata = JSON.parse(new TextDecoder().decode(bytes.subarray(0, newline)))
      const wasm = bytes.slice(newline + 1)
      if (!Array.isArray(metadata.diagnostics) || !metadata.timings || !WebAssembly.validate(wasm)) return undefined
      const result: CompileResult = { ...metadata, wasm }
      this.remember(key, result)
      return structuredClone(result)
    } catch {
      // Private browsing, storage quota, and old/corrupt entries must never prevent compilation.
      return undefined
    }
  }

  async put(key: string, result: CompileResult): Promise<void> {
    if (!result.wasm || result.diagnostics.some(d => d.severity === 'error')) return
    const { wasm, ...metadata } = result
    const body = new Blob([JSON.stringify(metadata) + '\n', new Uint8Array(wasm)])
    if (body.size > MAX_BYTES) return
    this.remember(key, result)
    try {
      const cache = await this.storage?.open(CACHE_NAME)
      if (!cache) return
      await cache.delete(key)
      await cache.put(key, new Response(body))
      const keys = await cache.keys()
      for (const old of keys.slice(0, Math.max(0, keys.length - MAX_ENTRIES))) await cache.delete(old)
    } catch {
      // The in-memory cache still works when persistent browser storage is unavailable.
    }
  }
}
