/// Runner worker: executes one compiled program with the WASI shim. One worker per run, so the main thread
/// can enforce the time limit by terminating it. Messages are described by RunRequest / RunEvent in types.ts.
import { runModule } from './wasi.ts'
import type { RunEvent, RunRequest } from './types.ts'

interface WorkerScope {
  onmessage: ((ev: MessageEvent<RunRequest>) => void) | null
  postMessage(msg: RunEvent): void
}
const scope = self as unknown as WorkerScope

// Output is batched: a program printing in a tight loop must not flood the main thread with messages.
let pending: { type: 'stdout' | 'stderr'; data: string }[] = []
let pendingBytes = 0
let lastFlush = 0

function flush() {
  for (const ev of pending) scope.postMessage(ev)
  pending = []
  pendingBytes = 0
  lastFlush = performance.now()
}

function push(type: 'stdout' | 'stderr', data: string) {
  const last = pending[pending.length - 1]
  if (last && last.type === type) last.data += data
  else pending.push({ type, data })
  pendingBytes += data.length
  if (pendingBytes > 8192 || performance.now() - lastFlush > 30) flush()
}

scope.onmessage = (ev) => {
  const req = ev.data
  if (req.type !== 'run') return
  const t0 = performance.now()
  lastFlush = t0
  const res = runModule(req.wasm, {
    args: req.args,
    env: req.env,
    stdin: req.stdin,
    onStdout: (s) => push('stdout', s),
    onStderr: (s) => push('stderr', s),
  })
  flush()
  scope.postMessage({ type: 'exit', exitCode: res.exitCode, durationMs: performance.now() - t0, error: res.error })
}
