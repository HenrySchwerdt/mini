// A WASI preview1 shim with an in-memory file system, for running minic.wasm in a worker (browser) or in node.
// Only what the compiler and wasi-libc's stdio need: args, fd_read/write/seek/close, fd_fdstat_*, fd_prestat_*, path_open,
// proc_exit. Every other function of the snapshot answers ENOSYS (a missing import would fail instantiation).
//
//   const fs = new Map([['/work/main.kt', bytes]])            // absolute path -> Uint8Array
//   const r = await runCommand(module, ['minic', '--check', '/work/main.kt'], fs)   // { exitCode, stdout, stderr }
// Files written by the program (fopen "wb") are stored in `fs`.

const ESUCCESS = 0, EBADF = 8, EINVAL = 28, ENOENT = 44, ENOSYS = 52, EEXIST = 20, EISDIR = 31
const FT_CHAR = 2, FT_DIR = 3, FT_REG = 4
const O_CREAT = 1, O_DIRECTORY = 2, O_EXCL = 4, O_TRUNC = 8
const WHENCE_SET = 0, WHENCE_CUR = 1, WHENCE_END = 2

class ProcExit extends Error {
    constructor(code) { super(`proc_exit(${code})`); this.code = code }
}

const concat = (parts, total) => {
    const out = new Uint8Array(total)
    let o = 0
    for (const p of parts) { out.set(p, o); o += p.length }
    return out
}

/** Instantiates and runs a WASI command module (`_start`) against [files]; resolves when the program exits. */
export async function runCommand(module, args, files, { stdin = new Uint8Array(0) } = {}) {
    const enc = new TextEncoder()
    const dec = new TextDecoder()
    const out = [[], []], outLen = [0, 0]
    // fd table: 0..2 stdio, 3 = preopened "/", >= 4 open files
    const fds = new Map()
    let nextFd = 4
    let mem
    let stdinPos = 0

    const u8 = () => new Uint8Array(mem.buffer)
    const dv = () => new DataView(mem.buffer)
    const argBytes = args.map(a => enc.encode(a + '\0'))

    const norm = p => {
        const parts = []
        for (const s of p.split('/')) {
            if (s === '' || s === '.') continue
            if (s === '..') parts.pop(); else parts.push(s)
        }
        return '/' + parts.join('/')
    }
    const isDir = p => {
        if (p === '/') return true
        const prefix = p + '/'
        for (const k of files.keys()) if (k.startsWith(prefix)) return true
        return false
    }

    const wasi = {
        args_sizes_get(argc, bufSize) {
            dv().setUint32(argc, args.length, true)
            dv().setUint32(bufSize, argBytes.reduce((n, b) => n + b.length, 0), true)
            return ESUCCESS
        },
        args_get(argv, buf) {
            let p = buf
            args.forEach((_, i) => {
                dv().setUint32(argv + 4 * i, p, true)
                u8().set(argBytes[i], p)
                p += argBytes[i].length
            })
            return ESUCCESS
        },
        environ_sizes_get(count, size) { dv().setUint32(count, 0, true); dv().setUint32(size, 0, true); return ESUCCESS },
        environ_get() { return ESUCCESS },
        clock_time_get(_id, _prec, ptr) { dv().setBigUint64(ptr, BigInt(Date.now()) * 1000000n, true); return ESUCCESS },
        random_get(ptr, len) { for (let i = 0; i < len; i++) u8()[ptr + i] = (Math.random() * 256) | 0; return ESUCCESS },
        sched_yield() { return ESUCCESS },
        proc_exit(code) { throw new ProcExit(code) },
        fd_prestat_get(fd, ptr) {
            if (fd !== 3) return EBADF
            dv().setUint8(ptr, 0) // directory
            dv().setUint32(ptr + 4, 1, true) // name length of "/"
            return ESUCCESS
        },
        fd_prestat_dir_name(fd, ptr, len) {
            if (fd !== 3) return EBADF
            if (len >= 1) u8()[ptr] = 0x2f
            return ESUCCESS
        },
        fd_fdstat_get(fd, ptr) {
            let type
            if (fd <= 2) type = FT_CHAR
            else if (fd === 3) type = FT_DIR
            else if (fds.has(fd)) type = FT_REG
            else return EBADF
            const v = dv()
            v.setUint8(ptr, type)
            v.setUint16(ptr + 2, 0, true)
            v.setBigUint64(ptr + 8, 0xffffffffffffffffn, true)
            v.setBigUint64(ptr + 16, 0xffffffffffffffffn, true)
            return ESUCCESS
        },
        fd_fdstat_set_flags() { return ESUCCESS },
        fd_close(fd) {
            const f = fds.get(fd)
            if (f) { flush(f); fds.delete(fd); return ESUCCESS }
            return fd <= 3 ? ESUCCESS : EBADF
        },
        path_open(dirfd, _dirflags, pathPtr, pathLen, oflags, _rb, _ri, _fdflags, fdPtr) {
            if (dirfd !== 3) return EBADF
            const rel = dec.decode(u8().subarray(pathPtr, pathPtr + pathLen))
            const path = norm('/' + rel)
            if (oflags & O_DIRECTORY) return isDir(path) ? ENOSYS : ENOENT
            if (isDir(path)) return EISDIR
            const exists = files.has(path)
            if (!exists && !(oflags & O_CREAT)) return ENOENT
            if (exists && (oflags & O_CREAT) && (oflags & O_EXCL)) return EEXIST
            // write handle: collects bytes, stored in the map on every write and on close
            const f = { path, pos: 0, data: exists && !(oflags & O_TRUNC) ? files.get(path) : new Uint8Array(0), dirty: false }
            if (!exists || (oflags & O_TRUNC)) files.set(path, f.data)
            const fd = nextFd++
            fds.set(fd, f)
            dv().setUint32(fdPtr, fd, true)
            return ESUCCESS
        },
        fd_read(fd, iovs, iovsLen, nreadPtr) {
            let total = 0
            const v = dv()
            if (fd === 0) {
                for (let i = 0; i < iovsLen; i++) {
                    const ptr = v.getUint32(iovs + 8 * i, true), len = v.getUint32(iovs + 8 * i + 4, true)
                    const n = Math.min(len, stdin.length - stdinPos)
                    u8().set(stdin.subarray(stdinPos, stdinPos + n), ptr)
                    stdinPos += n; total += n
                    if (n < len) break
                }
                v.setUint32(nreadPtr, total, true)
                return ESUCCESS
            }
            const f = fds.get(fd)
            if (!f) return EBADF
            for (let i = 0; i < iovsLen; i++) {
                const ptr = v.getUint32(iovs + 8 * i, true), len = v.getUint32(iovs + 8 * i + 4, true)
                const n = Math.max(0, Math.min(len, f.data.length - f.pos))
                u8().set(f.data.subarray(f.pos, f.pos + n), ptr)
                f.pos += n; total += n
                if (n < len) break
            }
            v.setUint32(nreadPtr, total, true)
            return ESUCCESS
        },
        fd_write(fd, iovs, iovsLen, nwrittenPtr) {
            let total = 0
            const v = dv()
            const f = fd > 3 ? fds.get(fd) : undefined
            if (fd > 3 && !f) return EBADF
            if (fd === 3) return EBADF
            for (let i = 0; i < iovsLen; i++) {
                const ptr = v.getUint32(iovs + 8 * i, true), len = v.getUint32(iovs + 8 * i + 4, true)
                const chunk = u8().slice(ptr, ptr + len)
                if (fd === 1 || fd === 2) { out[fd - 1].push(chunk); outLen[fd - 1] += len }
                else if (f) {
                    const end = f.pos + len
                    if (end > f.data.length) {
                        const grown = new Uint8Array(Math.max(end, f.data.length * 2))
                        grown.set(f.data)
                        f.size = Math.max(f.size ?? f.data.length, end)
                        f.data = grown
                    } else f.size = Math.max(f.size ?? f.data.length, end)
                    f.data.set(chunk, f.pos)
                    f.pos = end
                    f.dirty = true
                } else return EBADF
                total += len
            }
            v.setUint32(nwrittenPtr, total, true)
            return ESUCCESS
        },
        fd_seek(fd, offset, whence, newPtr) {
            const f = fds.get(fd)
            if (!f) return fd <= 2 ? 70 /* SPIPE */ : EBADF
            const size = f.dirty ? (f.size ?? f.data.length) : f.data.length
            const off = Number(offset)
            const pos = whence === WHENCE_SET ? off : whence === WHENCE_CUR ? f.pos + off : whence === WHENCE_END ? size + off : -1
            if (pos < 0) return EINVAL
            f.pos = pos
            dv().setBigUint64(newPtr, BigInt(pos), true)
            return ESUCCESS
        },
    }
    function flush(f) {
        if (f.dirty) { files.set(f.path, f.data.slice(0, f.size ?? f.data.length)); f.dirty = false }
    }
    const imports = new Proxy(wasi, { get: (t, k) => (k in t ? t[k] : () => ENOSYS) })

    const instance = await WebAssembly.instantiate(module, { wasi_snapshot_preview1: imports })
    mem = instance.exports.memory
    let exitCode = 0
    try {
        instance.exports._start()
    } catch (e) {
        if (e instanceof ProcExit) exitCode = e.code
        else if (e instanceof WebAssembly.RuntimeError || e instanceof RangeError) { exitCode = 134; const m = enc.encode(`\n${e.message}\n`); out[1].push(m); outLen[1] += m.length }
        else throw e
    }
    for (const f of fds.values()) flush(f)
    return {
        exitCode,
        stdout: dec.decode(concat(out[0], outLen[0])),
        stderr: dec.decode(concat(out[1], outLen[1])),
    }
}
