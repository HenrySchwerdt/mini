import type { RunEvent, RunOptions, RunRequest, RunResult } from './types.ts'

/**
 * Runs a compiled module in a fresh runner worker and enforces the limits by terminating it.
 * Needs a browser (Worker). The worker is created with the bundler-friendly `new URL(...)` form.
 */
export function runInWorker(wasm: Uint8Array, opts: RunOptions): Promise<RunResult> {
  return new Promise((resolve) => {
    const worker = new Worker(new URL('./runner.worker.ts', import.meta.url), { type: 'module' })
    const t0 = performance.now()
    const limit = opts.maxOutputBytes ?? 4 * 1024 * 1024
    let bytes = 0
    let done = false
    let timer: ReturnType<typeof setTimeout> | undefined

    const finish = (r: Omit<RunResult, 'durationMs'> & { durationMs?: number }) => {
      if (done) return
      done = true
      clearTimeout(timer)
      worker.terminate()
      resolve({ ...r, durationMs: r.durationMs ?? performance.now() - t0 })
    }
    if (opts.signal?.aborted) return finish({ exitCode: 130 })
    opts.signal?.addEventListener('abort', () => finish({ exitCode: 130 }), { once: true })
    timer = setTimeout(() => finish({ exitCode: 124, timedOut: true }), opts.timeoutMs)

    worker.onmessage = (ev: MessageEvent<RunEvent>) => {
      const m = ev.data
      if (m.type === 'exit') return finish({ exitCode: m.exitCode, durationMs: m.durationMs, error: m.error })
      bytes += m.data.length
      if (bytes > limit) return finish({ exitCode: 153, outputLimitExceeded: true })
      ;(m.type === 'stdout' ? opts.onStdout : opts.onStderr)(m.data)
    }
    worker.onerror = (ev) => finish({ exitCode: 134, error: ev.message || 'the runner worker failed' })

    const req: RunRequest = { type: 'run', wasm, stdin: opts.stdin, args: opts.args ?? ['main'], env: opts.env ?? {} }
    worker.postMessage(req)
  })
}
