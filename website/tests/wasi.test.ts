import { test } from 'node:test'
import assert from 'node:assert/strict'
import wabt from 'wabt'
import { ERRNO, Wasi, runModule } from '../.vitepress/theme/playground/wasi.ts'

const w = await wabt()
const compile = (wat: string): Uint8Array => w.parseWat('t.wat', wat).toBinary({}).buffer

function run(wat: string, opts: Parameters<typeof runModule>[1] = {}) {
  let out = ''
  let err = ''
  const res = runModule(compile(wat), { ...opts, onStdout: (s) => (out += s), onStderr: (s) => (err += s) })
  return { ...res, out, err }
}

const PRELUDE = `
  (import "wasi_snapshot_preview1" "fd_write" (func $fd_write (param i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "fd_read" (func $fd_read (param i32 i32 i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "proc_exit" (func $proc_exit (param i32)))
  (import "wasi_snapshot_preview1" "args_sizes_get" (func $args_sizes_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "environ_sizes_get" (func $environ_sizes_get (param i32 i32) (result i32)))
  (import "wasi_snapshot_preview1" "args_get" (func $args_get (param i32 i32) (result i32)))
  (memory (export "memory") 1)
`

test('hello: stdout, stderr and proc_exit', () => {
  const r = run(`(module ${PRELUDE}
    (data (i32.const 100) "hello\\n")
    (data (i32.const 120) "oops\\n")
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 100)) (i32.store (i32.const 4) (i32.const 6))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))
      (i32.store (i32.const 0) (i32.const 120)) (i32.store (i32.const 4) (i32.const 5))
      (drop (call $fd_write (i32.const 2) (i32.const 0) (i32.const 1) (i32.const 8)))
      (call $proc_exit (i32.const 3))
      unreachable))`)
  assert.equal(r.out, 'hello\n')
  assert.equal(r.err, 'oops\n')
  assert.equal(r.exitCode, 3)
  assert.equal(r.error, undefined)
})

test('a module that returns from _start exits with 0', () => {
  const r = run(`(module ${PRELUDE} (func (export "_start")))`)
  assert.equal(r.exitCode, 0)
})

test('a multi-byte character split across two writes arrives intact', () => {
  const r = run(`(module ${PRELUDE}
    (data (i32.const 100) "\\c3\\a9")
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 100)) (i32.store (i32.const 4) (i32.const 1))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))
      (i32.store (i32.const 0) (i32.const 101)) (i32.store (i32.const 4) (i32.const 1))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))))`)
  assert.equal(r.out, 'é')
})

test('gathered writes (several iovecs) are concatenated and nwritten is the total', () => {
  const r = run(`(module ${PRELUDE}
    (data (i32.const 100) "ab")
    (data (i32.const 110) "cde")
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 100)) (i32.store (i32.const 4) (i32.const 2))
      (i32.store (i32.const 8) (i32.const 110)) (i32.store (i32.const 12) (i32.const 3))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 2) (i32.const 32)))
      (call $proc_exit (i32.load (i32.const 32)))))`)
  assert.equal(r.out, 'abcde')
  assert.equal(r.exitCode, 5)
})

test('stdin is read until EOF, then fd_read returns 0 bytes', () => {
  // echo: read up to 64 bytes at 200, write them back; exit with the number of bytes of the second read.
  const r = run(
    `(module ${PRELUDE}
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 200)) (i32.store (i32.const 4) (i32.const 64))
      (drop (call $fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
      (i32.store (i32.const 4) (i32.load (i32.const 8)))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 16)))
      (i32.store (i32.const 4) (i32.const 64))
      (drop (call $fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
      (call $proc_exit (i32.load (i32.const 8)))))`,
    { stdin: 'line one\nzwei ü\n' },
  )
  assert.equal(r.out, 'line one\nzwei ü\n')
  assert.equal(r.exitCode, 0)
})

test('a short stdin buffer is delivered in pieces when the iovec is small', () => {
  const r = run(
    `(module ${PRELUDE}
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 200)) (i32.store (i32.const 4) (i32.const 2))
      (drop (call $fd_read (i32.const 0) (i32.const 0) (i32.const 1) (i32.const 8)))
      (call $proc_exit (i32.load (i32.const 8)))))`,
    { stdin: 'abcdef' },
  )
  assert.equal(r.exitCode, 2)
})

test('args and environ sizes', () => {
  const r = run(
    `(module ${PRELUDE}
    (func (export "_start")
      (drop (call $args_sizes_get (i32.const 0) (i32.const 4)))
      (drop (call $environ_sizes_get (i32.const 8) (i32.const 12)))
      ;; exit code = argc * 100 + envc * 10 + (argv bytes mod 10)
      (call $proc_exit (i32.add (i32.add (i32.mul (i32.load (i32.const 0)) (i32.const 10)) (i32.load (i32.const 8))) (i32.const 0)))))`,
    { args: ['prog', 'a'], env: { A: '1', B: '2', C: '3' } },
  )
  assert.equal(r.exitCode, 2 * 10 + 3)
})

test('args_get writes NUL-terminated strings and pointers', () => {
  const wasi = new Wasi({ args: ['prog', 'x y'] })
  const mod = new WebAssembly.Module(compile(`(module (memory (export "memory") 1) (func (export "_start")))`))
  const inst = new WebAssembly.Instance(mod, wasi.imports(mod))
  wasi.start(inst)
  const f = wasi.imports().wasi_snapshot_preview1 as Record<string, (...a: number[]) => number>
  const mem = inst.exports.memory as WebAssembly.Memory
  assert.equal(f.args_get(16, 64), ERRNO.SUCCESS)
  const dv = new DataView(mem.buffer)
  const bytes = new Uint8Array(mem.buffer)
  const read = (p: number) => {
    let e = p
    while (bytes[e] !== 0) e++
    return new TextDecoder().decode(bytes.subarray(p, e))
  }
  assert.equal(read(dv.getUint32(16, true)), 'prog')
  assert.equal(read(dv.getUint32(20, true)), 'x y')
})

test('clocks, random_get, prestat, seek, unknown fds', () => {
  const wasi = new Wasi({ realtimeNs: () => 1_700_000_000_000_000_000n, monotonicNs: () => 42n })
  const mod = new WebAssembly.Module(compile(`(module (memory (export "memory") 3) (func (export "_start")))`))
  const inst = new WebAssembly.Instance(mod, wasi.imports(mod))
  wasi.start(inst)
  const f = wasi.imports().wasi_snapshot_preview1 as Record<string, (...a: any[]) => number>
  const mem = inst.exports.memory as WebAssembly.Memory
  const dv = () => new DataView(mem.buffer)

  assert.equal(f.clock_time_get(0, 0n, 8), ERRNO.SUCCESS)
  assert.equal(dv().getBigUint64(8, true), 1_700_000_000_000_000_000n)
  assert.equal(f.clock_time_get(1, 0n, 8), ERRNO.SUCCESS)
  assert.equal(dv().getBigUint64(8, true), 42n)
  assert.equal(f.clock_time_get(9, 0n, 8), ERRNO.INVAL)

  // random_get fills more than 65536 bytes (crypto.getRandomValues' limit) without touching the neighbours
  const base = 70000
  new Uint8Array(mem.buffer).fill(0, base - 1, base + 70001)
  assert.equal(f.random_get(base, 70000), ERRNO.SUCCESS)
  const bytes = new Uint8Array(mem.buffer)
  assert.equal(bytes[base - 1], 0)
  assert.equal(bytes[base + 70000], 0)
  assert.ok(bytes.subarray(base, base + 70000).some((b) => b !== 0))
  assert.ok(bytes.subarray(base + 66000, base + 70000).some((b) => b !== 0), 'the part past 64 KiB is filled too')

  assert.equal(f.fd_prestat_get(3, 8), ERRNO.BADF, 'no preopened directories')
  assert.equal(f.fd_seek(1, 0n, 0, 8), ERRNO.SPIPE)
  assert.equal(f.fd_write(7, 0, 0, 8), ERRNO.BADF)
  assert.equal(f.fd_read(1, 0, 0, 8), ERRNO.BADF)
  assert.equal(f.path_open(3, 0, 0, 0, 0n, 0n, 0n, 0, 0), ERRNO.NOTCAPABLE)
  assert.equal(f.fd_fdstat_get(1, 8), ERRNO.SUCCESS)
  assert.equal(dv().getUint8(8), 2, 'stdio is a character device')
  assert.equal(f.fd_close(1), ERRNO.SUCCESS)
  assert.equal(f.fd_write(1, 0, 0, 8), ERRNO.BADF, 'closed')
})

test('poll_oneoff waits for a relative clock subscription', () => {
  let now = 0n
  const wasi = new Wasi({ monotonicNs: () => now })
  const mod = new WebAssembly.Module(compile(`(module (memory (export "memory") 1) (func (export "_start")))`))
  const inst = new WebAssembly.Instance(mod, wasi.imports(mod))
  wasi.start(inst)
  const f = wasi.imports().wasi_snapshot_preview1 as Record<string, (...a: any[]) => number>
  const dv = new DataView((inst.exports.memory as WebAssembly.Memory).buffer)
  dv.setBigUint64(0, 7n, true) // userdata
  dv.setUint8(8, 0) // clock
  dv.setUint32(16, 1, true) // monotonic
  dv.setBigUint64(24, 20_000_000n, true) // 20 ms
  dv.setUint16(40, 0, true) // relative
  const t0 = performance.now()
  assert.equal(f.poll_oneoff(0, 256, 1, 512), ERRNO.SUCCESS)
  assert.ok(performance.now() - t0 >= 15, 'slept')
  assert.equal(dv.getUint32(512, true), 1)
  assert.equal(dv.getBigUint64(256, true), 7n)
})

test('a trap is reported as exit code 134 with the error text; output before it is kept', () => {
  const r = run(`(module ${PRELUDE}
    (data (i32.const 100) "before\\n")
    (func (export "_start")
      (i32.store (i32.const 0) (i32.const 100)) (i32.store (i32.const 4) (i32.const 7))
      (drop (call $fd_write (i32.const 1) (i32.const 0) (i32.const 1) (i32.const 8)))
      unreachable))`)
  assert.equal(r.exitCode, 134)
  assert.match(r.error!, /RuntimeError/)
  assert.equal(r.out, 'before\n')
})

test('a module without _start is rejected with a readable error', () => {
  const r = run(`(module (memory (export "memory") 1))`)
  assert.equal(r.exitCode, 134)
  assert.match(r.error!, /_start/)
})

test('WASI imports the shim does not know get an ENOSYS stub, so the module still instantiates', () => {
  const r = run(`(module
    (import "wasi_snapshot_preview1" "proc_exit" (func $proc_exit (param i32)))
    (import "wasi_snapshot_preview1" "future_function" (func $ff (result i32)))
    (memory (export "memory") 1)
    (func (export "_start") (call $proc_exit (call $ff))))`)
  assert.equal(r.exitCode, ERRNO.NOSYS)
})
