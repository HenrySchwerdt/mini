/**
 * A small WASI preview1 (wasi_snapshot_preview1) shim for running one compiled Mini program.
 *
 * Supported: stdio (fd 0 reads from a preset buffer, fd 1/2 are streamed to callbacks), args, environ,
 * clocks, random_get, proc_exit, sched_yield, poll_oneoff for clock subscriptions. There is no file system:
 * fd_prestat_get reports no preopened directories, path_* calls fail. Every other function of the snapshot
 * answers ENOSYS, so modules that import more than they use still instantiate.
 *
 * This file has no DOM or worker dependency, so it runs in the runner worker and under `node --test`.
 */

export const ERRNO = {
  SUCCESS: 0,
  BADF: 8,
  INVAL: 28,
  IO: 29,
  NOENT: 44,
  NOSYS: 52,
  NOTSUP: 58,
  SPIPE: 70,
  NOTCAPABLE: 76,
} as const

const FILETYPE_CHARACTER_DEVICE = 2
const CLOCK_REALTIME = 0
const CLOCK_MONOTONIC = 1
const CLOCK_PROCESS_CPUTIME = 2
const CLOCK_THREAD_CPUTIME = 3

/** Thrown out of the module by proc_exit. */
export class ProcExit extends Error {
  code: number
  constructor(code: number) {
    super(`proc_exit(${code})`)
    this.name = 'ProcExit'
    this.code = code
  }
}

export interface WasiOptions {
  args?: string[]
  env?: Record<string, string>
  stdin?: string | Uint8Array
  onStdout?: (text: string) => void
  onStderr?: (text: string) => void
  /** Clock overrides, for tests. Both return nanoseconds. */
  realtimeNs?: () => bigint
  monotonicNs?: () => bigint
}

const WASI_FUNCTIONS = [
  'args_get', 'args_sizes_get', 'environ_get', 'environ_sizes_get', 'clock_res_get', 'clock_time_get',
  'fd_advise', 'fd_allocate', 'fd_close', 'fd_datasync', 'fd_fdstat_get', 'fd_fdstat_set_flags',
  'fd_fdstat_set_rights', 'fd_filestat_get', 'fd_filestat_set_size', 'fd_filestat_set_times', 'fd_pread',
  'fd_prestat_get', 'fd_prestat_dir_name', 'fd_pwrite', 'fd_read', 'fd_readdir', 'fd_renumber', 'fd_seek',
  'fd_sync', 'fd_tell', 'fd_write', 'path_create_directory', 'path_filestat_get', 'path_filestat_set_times',
  'path_link', 'path_open', 'path_readlink', 'path_remove_directory', 'path_rename', 'path_symlink',
  'path_unlink_file', 'poll_oneoff', 'proc_exit', 'proc_raise', 'random_get', 'sched_yield', 'sock_accept',
  'sock_recv', 'sock_send', 'sock_shutdown',
]

export class Wasi {
  private memory: WebAssembly.Memory | undefined
  private readonly args: Uint8Array[]
  private readonly environ: Uint8Array[]
  private readonly stdin: Uint8Array
  private stdinPos = 0
  private readonly onStdout: (text: string) => void
  private readonly onStderr: (text: string) => void
  private readonly decoders = [new TextDecoder('utf-8'), new TextDecoder('utf-8')]
  private readonly realtimeNs: () => bigint
  private readonly monotonicNs: () => bigint
  private readonly closed = new Set<number>()

  constructor(opts: WasiOptions = {}) {
    const enc = new TextEncoder()
    const cstr = (s: string) => enc.encode(s + '\0')
    this.args = (opts.args ?? ['main']).map(cstr)
    this.environ = Object.entries(opts.env ?? {}).map(([k, v]) => cstr(`${k}=${v}`))
    this.stdin = typeof opts.stdin === 'string' ? enc.encode(opts.stdin) : (opts.stdin ?? new Uint8Array(0))
    this.onStdout = opts.onStdout ?? (() => {})
    this.onStderr = opts.onStderr ?? (() => {})
    this.realtimeNs = opts.realtimeNs ?? (() => BigInt(Math.round((performance.timeOrigin + performance.now()) * 1e6)))
    this.monotonicNs = opts.monotonicNs ?? (() => BigInt(Math.round(performance.now() * 1e6)))
  }

  /** Builds the import object for `module`: the implemented functions plus ENOSYS stubs for any other WASI import. */
  imports(module?: WebAssembly.Module): WebAssembly.Imports {
    const impl = this.functions()
    const ns: Record<string, (...a: any[]) => number | void> = {}
    for (const name of WASI_FUNCTIONS) ns[name] = impl[name] ?? (() => ERRNO.NOSYS)
    if (module) {
      for (const imp of WebAssembly.Module.imports(module)) {
        if (imp.kind === 'function' && imp.module === 'wasi_snapshot_preview1' && !(imp.name in ns)) {
          ns[imp.name] = () => ERRNO.NOSYS
        }
      }
    }
    return { wasi_snapshot_preview1: ns }
  }

  /**
   * Runs a command module (`_start`) or a reactor (`_initialize`, then nothing to call: reactors have no main).
   * Resolves with the exit code. Traps propagate as WebAssembly.RuntimeError.
   */
  start(instance: WebAssembly.Instance): number {
    const memory = instance.exports.memory
    if (!(memory instanceof WebAssembly.Memory)) throw new Error('module does not export its memory')
    this.memory = memory
    const exports = instance.exports as Record<string, unknown>
    try {
      if (typeof exports._initialize === 'function') (exports._initialize as () => void)()
      if (typeof exports._start !== 'function') throw new Error('module exports no _start')
      ;(exports._start as () => void)()
    } catch (e) {
      if (e instanceof ProcExit) {
        this.flush()
        return e.code
      }
      this.flush()
      throw e
    }
    this.flush()
    return 0
  }

  private flush() {
    for (const fd of [1, 2]) {
      const rest = this.decoders[fd - 1].decode()
      if (rest) (fd === 1 ? this.onStdout : this.onStderr)(rest)
    }
  }

  private view() {
    return new DataView(this.memory!.buffer)
  }
  private bytes() {
    return new Uint8Array(this.memory!.buffer)
  }

  private writeStrings(strings: Uint8Array[], ptrs: number, buf: number) {
    const dv = this.view()
    const mem = this.bytes()
    for (const s of strings) {
      dv.setUint32(ptrs, buf, true)
      ptrs += 4
      mem.set(s, buf)
      buf += s.length
    }
  }

  private functions(): Record<string, (...a: any[]) => number | void> {
    const sum = (a: Uint8Array[]) => a.reduce((n, s) => n + s.length, 0)
    return {
      args_sizes_get: (argc: number, size: number) => {
        const dv = this.view()
        dv.setUint32(argc, this.args.length, true)
        dv.setUint32(size, sum(this.args), true)
        return ERRNO.SUCCESS
      },
      args_get: (ptrs: number, buf: number) => (this.writeStrings(this.args, ptrs, buf), ERRNO.SUCCESS),
      environ_sizes_get: (count: number, size: number) => {
        const dv = this.view()
        dv.setUint32(count, this.environ.length, true)
        dv.setUint32(size, sum(this.environ), true)
        return ERRNO.SUCCESS
      },
      environ_get: (ptrs: number, buf: number) => (this.writeStrings(this.environ, ptrs, buf), ERRNO.SUCCESS),

      clock_res_get: (id: number, out: number) => {
        if (id > CLOCK_THREAD_CPUTIME) return ERRNO.INVAL
        this.view().setBigUint64(out, 1000n, true)
        return ERRNO.SUCCESS
      },
      clock_time_get: (id: number, _precision: bigint, out: number) => {
        let t: bigint
        if (id === CLOCK_REALTIME) t = this.realtimeNs()
        else if (id === CLOCK_MONOTONIC || id === CLOCK_PROCESS_CPUTIME || id === CLOCK_THREAD_CPUTIME) t = this.monotonicNs()
        else return ERRNO.INVAL
        this.view().setBigUint64(out, t, true)
        return ERRNO.SUCCESS
      },

      random_get: (ptr: number, len: number) => {
        const mem = this.bytes()
        // crypto.getRandomValues accepts at most 65536 bytes per call.
        for (let off = 0; off < len; off += 65536) {
          crypto.getRandomValues(mem.subarray(ptr + off, ptr + Math.min(len, off + 65536)))
        }
        return ERRNO.SUCCESS
      },

      proc_exit: (code: number) => {
        throw new ProcExit(code)
      },
      proc_raise: () => ERRNO.NOSYS,
      sched_yield: () => ERRNO.SUCCESS,

      fd_write: (fd: number, iovs: number, iovsLen: number, nwritten: number) => {
        if ((fd !== 1 && fd !== 2) || this.closed.has(fd)) return ERRNO.BADF
        const dv = this.view()
        const mem = this.bytes()
        let total = 0
        const dec = this.decoders[fd - 1]
        const out = fd === 1 ? this.onStdout : this.onStderr
        for (let i = 0; i < iovsLen; i++) {
          const ptr = dv.getUint32(iovs + i * 8, true)
          const len = dv.getUint32(iovs + i * 8 + 4, true)
          if (len === 0) continue
          // stream: true keeps a multi-byte character that is split across writes intact.
          const text = dec.decode(mem.subarray(ptr, ptr + len), { stream: true })
          if (text) out(text)
          total += len
        }
        dv.setUint32(nwritten, total, true)
        return ERRNO.SUCCESS
      },
      fd_read: (fd: number, iovs: number, iovsLen: number, nread: number) => {
        if (fd !== 0 || this.closed.has(fd)) return ERRNO.BADF
        const dv = this.view()
        const mem = this.bytes()
        let total = 0
        for (let i = 0; i < iovsLen; i++) {
          const ptr = dv.getUint32(iovs + i * 8, true)
          const len = dv.getUint32(iovs + i * 8 + 4, true)
          const n = Math.min(len, this.stdin.length - this.stdinPos)
          if (n <= 0) break
          mem.set(this.stdin.subarray(this.stdinPos, this.stdinPos + n), ptr)
          this.stdinPos += n
          total += n
          if (n < len) break
        }
        dv.setUint32(nread, total, true)
        return ERRNO.SUCCESS
      },
      fd_close: (fd: number) => {
        if (fd > 2 || this.closed.has(fd)) return ERRNO.BADF
        this.closed.add(fd)
        return ERRNO.SUCCESS
      },
      fd_fdstat_get: (fd: number, out: number) => {
        if (fd > 2 || this.closed.has(fd)) return ERRNO.BADF
        const dv = this.view()
        dv.setUint8(out, FILETYPE_CHARACTER_DEVICE)
        dv.setUint8(out + 1, 0)
        dv.setUint16(out + 2, 0, true) // fdflags
        dv.setBigUint64(out + 8, fd === 0 ? 0x2n : 0x40n, true) // rights base: FD_READ / FD_WRITE
        dv.setBigUint64(out + 16, 0n, true)
        return ERRNO.SUCCESS
      },
      fd_fdstat_set_flags: (fd: number) => (fd <= 2 ? ERRNO.SUCCESS : ERRNO.BADF),
      fd_filestat_get: (fd: number, out: number) => {
        if (fd > 2) return ERRNO.BADF
        const dv = this.view()
        new Uint8Array(this.memory!.buffer, out, 64).fill(0)
        dv.setUint8(out + 16, FILETYPE_CHARACTER_DEVICE)
        return ERRNO.SUCCESS
      },
      fd_seek: (fd: number) => (fd <= 2 ? ERRNO.SPIPE : ERRNO.BADF),
      fd_tell: (fd: number) => (fd <= 2 ? ERRNO.SPIPE : ERRNO.BADF),
      fd_pread: (fd: number) => (fd <= 2 ? ERRNO.SPIPE : ERRNO.BADF),
      fd_pwrite: (fd: number) => (fd <= 2 ? ERRNO.SPIPE : ERRNO.BADF),
      fd_sync: (fd: number) => (fd <= 2 ? ERRNO.SUCCESS : ERRNO.BADF),
      fd_datasync: (fd: number) => (fd <= 2 ? ERRNO.SUCCESS : ERRNO.BADF),

      // No preopened directories: the first fd_prestat_get fails with BADF, which ends the libc scan.
      fd_prestat_get: () => ERRNO.BADF,
      fd_prestat_dir_name: () => ERRNO.BADF,
      fd_readdir: () => ERRNO.BADF,
      path_open: () => ERRNO.NOTCAPABLE,
      path_filestat_get: () => ERRNO.NOTCAPABLE,
      path_create_directory: () => ERRNO.NOTCAPABLE,
      path_remove_directory: () => ERRNO.NOTCAPABLE,
      path_unlink_file: () => ERRNO.NOTCAPABLE,
      path_rename: () => ERRNO.NOTCAPABLE,
      path_readlink: () => ERRNO.NOTCAPABLE,

      poll_oneoff: (inPtr: number, outPtr: number, nsubs: number, neventsPtr: number) => {
        // Only clock subscriptions are supported (that is what sleep() uses): wait for the earliest one.
        const dv = this.view()
        let waitNs = -1n
        const events: { userdata: bigint }[] = []
        for (let i = 0; i < nsubs; i++) {
          const base = inPtr + i * 48
          const userdata = dv.getBigUint64(base, true)
          const tag = dv.getUint8(base + 8)
          if (tag !== 0) return ERRNO.NOTSUP
          const clockId = dv.getUint32(base + 16, true)
          const timeout = dv.getBigUint64(base + 24, true)
          const flags = dv.getUint16(base + 40, true)
          let rel = timeout
          if (flags & 1) {
            const now = clockId === CLOCK_REALTIME ? this.realtimeNs() : this.monotonicNs()
            rel = timeout > now ? timeout - now : 0n
          }
          if (waitNs < 0n || rel < waitNs) waitNs = rel
          events.push({ userdata })
        }
        sleepNs(waitNs < 0n ? 0n : waitNs)
        // Report every subscription as expired (the earliest one is, the others are a close enough approximation
        // for a single-threaded program that polls only sleeps).
        const dv2 = this.view()
        events.forEach((ev, i) => {
          const o = outPtr + i * 32
          new Uint8Array(this.memory!.buffer, o, 32).fill(0)
          dv2.setBigUint64(o, ev.userdata, true)
          dv2.setUint16(o + 8, 0, true) // error
          dv2.setUint8(o + 10, 0) // type: clock
        })
        dv2.setUint32(neventsPtr, events.length, true)
        return ERRNO.SUCCESS
      },
    }
  }
}

function sleepNs(ns: bigint) {
  const ms = Number(ns / 1_000_000n)
  if (ms <= 0) return
  try {
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
  } catch {
    const end = performance.now() + ms
    while (performance.now() < end) { /* busy wait: Atomics.wait is not allowed on this thread */ }
  }
}

export interface RunModuleResult {
  exitCode: number
  error?: string
}

/** Compiles, instantiates and runs a WASI command module synchronously. Traps become exit code 134 + error. */
export function runModule(wasm: Uint8Array, opts: WasiOptions): RunModuleResult {
  const wasi = new Wasi(opts)
  try {
    const module = new WebAssembly.Module(wasm as BufferSource)
    const instance = new WebAssembly.Instance(module, wasi.imports(module))
    return { exitCode: wasi.start(instance) }
  } catch (e) {
    const err = e as Error
    return { exitCode: 134, error: `${err.name}: ${err.message}` }
  }
}
