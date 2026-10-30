package mini.backend

/*
 * WebAssembly libraries (`minic-native --target=wasm32 --emit=wasm-lib`): the types that may cross the boundary, and the JavaScript
 * glue (`<name>.mjs`) and TypeScript declarations (`<name>.d.ts`) generated for the exported functions. The export wrappers
 * themselves are LLVM IR (llvm/Lowering.kt, `emitWasmLib`). ABI, summarised (website/guide/wasm-library.md has the details):
 *
 *   Int, Boolean -> i32 (Boolean 0/1)   Long -> i64   Float -> f32   Double -> f64
 *   String, IntArray, LongArray, DoubleArray, FloatArray as parameter -> (pointer, length): UTF-8 bytes / elements the caller
 *       wrote into memory obtained from `mini_alloc` (copied by the callee, the caller frees them with `mini_free`)
 *   String, arrays as result -> pointer to a malloc'ed copy (UTF-8 / elements), its length from `mini_result_len()`, freed with `mini_free`
 *   class types -> an opaque handle (pointer); a result handle is owned by the caller and released with `mini_release`
 *   errors: an exception that escapes an export makes the export return a dummy value; `mini_take_error()` then gives the message
 */

/** One `@WasmExport` function: the name of the wasm export and the C name of the MIR function it calls. */
class MirWasmExport(val name: String, val cName: String)

enum class WasmKind { I32, I64, F32, F64, BOOL, UNIT, STRING, INT_ARRAY, LONG_ARRAY, DOUBLE_ARRAY, FLOAT_ARRAY, HANDLE }

object WasmAbi {
    /** The reserved names of the module's own exports: a user function must not use them. */
    fun isReservedExportName(n: String): Boolean = n == "memory" || n == "load" || n == "MiniError" || n.startsWith("mini_") || n.startsWith("_")

    /** How [t] crosses the boundary, or null when it cannot. */
    fun kindOf(t: MirType): WasmKind? {
        if (t === TInt) return WasmKind.I32
        if (t === TLong) return WasmKind.I64
        if (t === TFloat) return WasmKind.F32
        if (t === TDouble) return WasmKind.F64
        if (t === TBoolean) return WasmKind.BOOL
        if (t === TUnit) return WasmKind.UNIT
        if (t === TString) return WasmKind.STRING
        if (t is TObject && !t.nullable) {
            return when (t.cName) {
                "MkIntArray" -> WasmKind.INT_ARRAY
                "MkLongArray" -> WasmKind.LONG_ARRAY
                "MkDoubleArray" -> WasmKind.DOUBLE_ARRAY
                "MkFloatArray" -> WasmKind.FLOAT_ARRAY
                "MkObjectArray", "MkBooleanArray", "MkCharArray", ANY_CNAME -> null
                else -> WasmKind.HANDLE
            }
        }
        return null
    }

    fun isArray(k: WasmKind): Boolean = k == WasmKind.INT_ARRAY || k == WasmKind.LONG_ARRAY || k == WasmKind.DOUBLE_ARRAY || k == WasmKind.FLOAT_ARRAY

    /** The `kind` argument of mk_wasm_array_in / mk_wasm_array_out (runtime/src/wasmlib.c). */
    fun arrayCode(k: WasmKind): Int = when (k) {
        WasmKind.INT_ARRAY -> 0
        WasmKind.LONG_ARRAY -> 1
        WasmKind.DOUBLE_ARRAY -> 2
        else -> 3
    }

    /** Number of wasm parameters one Kotlin parameter of kind [k] takes: (pointer, length) for strings and arrays. */
    fun wasmParams(k: WasmKind): Int = if (k == WasmKind.STRING || isArray(k)) 2 else 1
}

/** JavaScript glue and TypeScript declarations of a module with [MirModule.wasmExports]. */
object WasmGlue {
    private val JS_RESERVED = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "enum", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this",
        "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static", "await", "arguments", "eval", "undefined",
        // names the generated wrappers use
        "num", "putString", "putArray", "ptrOf", "check", "takeString", "takeArray", "Handle", "TOKEN", "inst", "tmp", "res", "load", "MiniError",
    )

    private class Fn(val export: MirWasmExport, val f: MirFunction, val argNames: List<String>)

    private fun jsParamName(n: String, taken: List<String>): String {
        var s = n
        if (s.isEmpty() || JS_RESERVED.contains(s)) s = s + "_"
        while (taken.contains(s)) s = s + "_"
        return s
    }

    private fun functions(m: MirModule): List<Fn> {
        val byName = HashMap<String, MirFunction>()
        for (f in m.functions) byName[f.cName] = f
        val out = ArrayList<Fn>()
        for (x in m.wasmExports) {
            val f = byName[x.cName] ?: throw EmitError("exported function ${x.cName} is missing")
            val names = ArrayList<String>()
            for (p in f.params) names.add(jsParamName(p.name, names))
            out.add(Fn(x, f, names))
        }
        return out
    }

    /** The JavaScript class name of every handle class (by C name of the Kotlin class), unique among themselves and the function names. */
    private fun handleNames(fns: List<Fn>): LinkedHashMap<String, String> {
        val used = HashSet<String>()
        used.add("load"); used.add("MiniError"); used.add("Handle")
        for (fn in fns) used.add(fn.export.name)
        val out = LinkedHashMap<String, String>()
        fun see(t: MirType) {
            if (t !is TObject || WasmAbi.kindOf(t) != WasmKind.HANDLE || out.containsKey(t.cName)) return
            var base = t.qualifiedName.substring(t.qualifiedName.lastIndexOf('.') + 1)
            if (base.isEmpty() || !(base[0].isLetter() || base[0] == '_')) base = "Handle" + base
            var n = base
            var k = 2
            while (used.contains(n)) { n = base + k; k++ }
            used.add(n)
            out[t.cName] = n
        }
        for (fn in fns) {
            for (p in fn.f.params) see(p.type)
            see(fn.f.returnType)
        }
        return out
    }

    private fun tsType(t: MirType, classes: Map<String, String>, result: Boolean): String {
        val k = WasmAbi.kindOf(t) ?: throw EmitError("type ${t.displayName} cannot be exported")
        return when (k) {
            WasmKind.I32, WasmKind.F32, WasmKind.F64 -> "number"
            WasmKind.I64 -> if (result) "bigint" else "bigint | number"
            WasmKind.BOOL -> "boolean"
            WasmKind.UNIT -> "void"
            WasmKind.STRING -> "string"
            WasmKind.INT_ARRAY -> if (result) "Int32Array" else "Int32Array | number[]"
            WasmKind.LONG_ARRAY -> if (result) "BigInt64Array" else "BigInt64Array | bigint[]"
            WasmKind.DOUBLE_ARRAY -> if (result) "Float64Array" else "Float64Array | number[]"
            WasmKind.FLOAT_ARRAY -> if (result) "Float32Array" else "Float32Array | number[]"
            WasmKind.HANDLE -> classes[(t as TObject).cName] ?: "unknown"
        }
    }

    private fun ctor(k: WasmKind): String = when (k) {
        WasmKind.INT_ARRAY -> "Int32Array"
        WasmKind.LONG_ARRAY -> "BigInt64Array"
        WasmKind.DOUBLE_ARRAY -> "Float64Array"
        else -> "Float32Array"
    }

    /** `<name>.d.ts`. */
    fun dts(m: MirModule): String {
        val fns = functions(m)
        val classes = handleNames(fns)
        val o = StringBuilder()
        o.append("// Generated by minic-native (--emit=wasm-lib) from ").append(m.sourceName).append(". Do not edit.\n\n")
        o.append("/** The message of an exception that escaped an exported Kotlin function, like `kotlin.IllegalStateException: text`. */\n")
        o.append("export declare class MiniError extends Error {}\n\n")
        o.append("/** Where to get the module from: a URL, a `fetch` response, the bytes, or a compiled `WebAssembly.Module`. */\n")
        o.append("export type Source = string | URL | Response | Promise<Response> | BufferSource | WebAssembly.Module\n\n")
        o.append("export interface LoadOptions {\n")
        o.append("    /** Receives what the Kotlin code prints to standard output (default: `process.stdout` in Node, `console.log` elsewhere). */\n")
        o.append("    stdout?: (text: string) => void\n")
        o.append("    /** Receives what is printed to standard error (default: `process.stderr` in Node, `console.error` elsewhere). */\n")
        o.append("    stderr?: (text: string) => void\n")
        o.append("}\n\n")
        for (n in classes.values) {
            o.append("/** An object of the module, owned by JavaScript until `dispose()` (it is also released when the wrapper is garbage collected). */\n")
            o.append("export declare class ").append(n).append(" {\n")
            o.append("    private constructor()\n")
            o.append("    private readonly __brand_").append(n).append(": never\n")
            o.append("    readonly disposed: boolean\n")
            o.append("    dispose(): void\n")
            o.append("    [Symbol.dispose](): void\n")
            o.append("}\n\n")
        }
        o.append("export interface Library {\n")
        for (fn in fns) {
            o.append("    /** `").append(kotlinSignature(fn)).append("` */\n")
            o.append("    ").append(fn.export.name).append("(")
            for ((i, p) in fn.f.params.withIndex()) {
                if (i > 0) o.append(", ")
                o.append(fn.argNames[i]).append(": ").append(tsType(p.type, classes, false))
            }
            o.append("): ").append(tsType(fn.f.returnType, classes, true)).append("\n")
        }
        o.append("    /** The raw exports of the wasm instance (`memory`, `mini_alloc`, ...). */\n")
        o.append("    readonly raw: WebAssembly.Exports\n")
        o.append("}\n\n")
        o.append("/** Compiles and starts the module (runtime initialisation and top-level properties included). */\n")
        o.append("export declare function load(source?: Source, options?: LoadOptions): Promise<Library>\n")
        o.append("export default load\n")
        return o.toString()
    }

    private fun kotlinSignature(fn: Fn): String {
        val s = StringBuilder("fun ").append(fn.f.qualifiedName.substring(fn.f.qualifiedName.lastIndexOf('.') + 1)).append("(")
        for ((i, p) in fn.f.params.withIndex()) {
            if (i > 0) s.append(", ")
            s.append(p.name).append(": ").append(p.type.displayName)
        }
        s.append(")")
        if (fn.f.returnType !== TUnit) s.append(": ").append(fn.f.returnType.displayName)
        return s.toString()
    }

    /** `<name>.mjs`: the ES module that loads [wasmFile] (relative to the module) and wraps the exports. */
    fun mjs(m: MirModule, wasmFile: String): String {
        val fns = functions(m)
        val classes = handleNames(fns)
        val o = StringBuilder()
        o.append("// Generated by minic-native (--emit=wasm-lib) from ").append(m.sourceName).append(". Do not edit.\n")
        o.append("const WASM_FILE = ").append(jsString(wasmFile)).append("\n")
        o.append(RUNTIME_JS)
        for (n in classes.values) o.append("export class ").append(n).append(" extends Handle {}\n")
        o.append("\nfunction makeLibrary(inst) {\n")
        o.append("    return {\n")
        for (fn in fns) {
            val pnames = fn.argNames
            o.append("        ").append(fn.export.name).append("(").append(pnames.joinToString(", ")).append(") {\n")
            val args = ArrayList<String>()
            var needsFree = false
            for ((i, p) in fn.f.params.withIndex()) {
                val k = WasmAbi.kindOf(p.type) ?: throw EmitError("type ${p.type.displayName} cannot be exported")
                val v = pnames[i]
                when (k) {
                    WasmKind.I32, WasmKind.F32, WasmKind.F64 -> args.add("num(" + jsString(v) + ", " + v + ")")
                    WasmKind.I64 -> args.add("BigInt.asIntN(64, BigInt(" + v + "))")
                    WasmKind.BOOL -> args.add("(" + v + " ? 1 : 0)")
                    WasmKind.STRING -> { args.add("...putString(inst, " + v + ", tmp)"); needsFree = true }
                    WasmKind.HANDLE -> args.add("ptrOf(" + v + ", " + classes[(p.type as TObject).cName] + ")")
                    WasmKind.UNIT -> {}
                    else -> { args.add("...putArray(inst, " + v + ", " + ctor(k) + ", tmp)"); needsFree = true }
                }
            }
            val rk = WasmAbi.kindOf(fn.f.returnType) ?: throw EmitError("type ${fn.f.returnType.displayName} cannot be exported")
            val call = "inst." + fn.export.name + "(" + args.joinToString(", ") + ")"
            if (needsFree) {
                o.append("            const tmp = []\n")
                o.append("            let res\n")
                o.append("            try { res = ").append(call).append(" } finally { for (const p of tmp) inst.mini_free(p) }\n")
            } else {
                o.append("            const res = ").append(call).append("\n")
            }
            o.append("            check(inst)\n")
            when (rk) {
                WasmKind.UNIT -> {}
                WasmKind.BOOL -> o.append("            return res !== 0\n")
                WasmKind.STRING -> o.append("            return takeString(inst, res)\n")
                WasmKind.HANDLE -> o.append("            return new ").append(classes[(fn.f.returnType as TObject).cName]).append("(TOKEN, inst, res)\n")
                WasmKind.I32, WasmKind.I64, WasmKind.F32, WasmKind.F64 -> o.append("            return res\n")
                else -> o.append("            return takeArray(inst, res, ").append(ctor(rk)).append(")\n")
            }
            o.append("        },\n")
        }
        o.append("        raw: inst,\n")
        o.append("    }\n")
        o.append("}\n\n")
        o.append("export async function load(source, options = {}) {\n")
        o.append("    return makeLibrary(await instantiate(source, options))\n")
        o.append("}\n")
        o.append("export default load\n")
        return o.toString()
    }

    private fun jsString(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            if (c == '"' || c == '\\') b.append('\\')
            b.append(c)
        }
        return b.append('"').toString()
    }

    /** The module-independent part of the glue (no `$` characters: this is a Kotlin raw string). */
    private const val RUNTIME_JS = """
const enc = new TextEncoder()
const dec = new TextDecoder()
const TOKEN = Symbol('token')
const PTR = Symbol('ptr')
const RT = Symbol('runtime')

export class MiniError extends Error {
    constructor(message) {
        super(message)
        this.name = 'MiniError'
    }
}

const registry = typeof FinalizationRegistry === 'function' ? new FinalizationRegistry(([rt, p]) => rt.mini_release(p)) : null

/** Base of the classes of objects that live in the module. */
class Handle {
    constructor(token, rt, ptr) {
        if (token !== TOKEN) throw new TypeError('objects of the module are created by its functions')
        this[RT] = rt
        this[PTR] = ptr
        if (registry) registry.register(this, [rt, ptr], this)
    }
    get disposed() { return this[PTR] === 0 }
    dispose() {
        const p = this[PTR]
        if (p === 0) return
        this[PTR] = 0
        if (registry) registry.unregister(this)
        this[RT].mini_release(p)
    }
}
if (Symbol.dispose) Handle.prototype[Symbol.dispose] = Handle.prototype.dispose

function ptrOf(h, cls) {
    if (!(h instanceof cls)) throw new TypeError('expected a ' + cls.name)
    const p = h[PTR]
    if (p === 0) throw new Error('this ' + cls.name + ' was disposed')
    return p
}

function num(name, v) {
    if (typeof v !== 'number') throw new TypeError(name + ' must be a number')
    return v
}

function check(x) {
    const p = x.mini_take_error()
    if (p === 0) return
    const bytes = new Uint8Array(x.memory.buffer)
    let e = p
    while (bytes[e] !== 0) e++
    throw new MiniError(dec.decode(bytes.subarray(p, e)))
}

function putString(x, s, t) {
    if (typeof s !== 'string') throw new TypeError('expected a string')
    const b = enc.encode(s)
    const p = x.mini_alloc(b.length)
    t.push(p)
    new Uint8Array(x.memory.buffer, p, b.length).set(b)
    return [p, b.length]
}

function putArray(x, a, Ctor, t) {
    const v = a instanceof Ctor ? a : Ctor.from(a)
    const p = x.mini_alloc(v.byteLength)
    t.push(p)
    new Uint8Array(x.memory.buffer, p, v.byteLength).set(new Uint8Array(v.buffer, v.byteOffset, v.byteLength))
    return [p, v.length]
}

function takeString(x, p) {
    const n = x.mini_result_len()
    const s = dec.decode(new Uint8Array(x.memory.buffer, p, n))
    x.mini_free(p)
    return s
}

function takeArray(x, p, Ctor) {
    const n = x.mini_result_len()
    const out = new Ctor(x.memory.buffer.slice(p, p + n * Ctor.BYTES_PER_ELEMENT))
    x.mini_free(p)
    return out
}

function lineWriter(log) {
    let pending = ''
    return text => {
        pending += text
        const i = pending.lastIndexOf('\n')
        if (i >= 0) {
            for (const line of pending.slice(0, i).split('\n')) log(line)
            pending = pending.slice(i + 1)
        }
    }
}

function defaultOut(stream, log) {
    if (typeof process !== 'undefined' && process[stream] && process[stream].write) return text => { process[stream].write(text) }
    return lineWriter(log)
}

/** The few WASI functions the C runtime needs: standard output / error, clock, random numbers; everything else is "not supported". */
function wasi(getMemory, options) {
    const out = [null, options.stdout || defaultOut('stdout', console.log), options.stderr || defaultOut('stderr', console.error)]
    const decoders = [null, new TextDecoder(), new TextDecoder()]
    const view = () => new DataView(getMemory().buffer)
    const ENOSYS = 52
    return {
        fd_write(fd, iovs, count, written) {
            if (fd !== 1 && fd !== 2) return 8
            const v = view()
            const bytes = new Uint8Array(getMemory().buffer)
            let total = 0
            let text = ''
            for (let i = 0; i < count; i++) {
                const p = v.getUint32(iovs + i * 8, true)
                const n = v.getUint32(iovs + i * 8 + 4, true)
                text += decoders[fd].decode(bytes.subarray(p, p + n), { stream: true })
                total += n
            }
            if (text) out[fd](text)
            v.setUint32(written, total, true)
            return 0
        },
        fd_read(fd, iovs, count, nread) { view().setUint32(nread, 0, true); return 0 },
        fd_close() { return 0 },
        fd_seek() { return 70 },
        fd_fdstat_get(fd, ptr) {
            if (fd > 2) return 8
            const v = view()
            for (let i = 0; i < 24; i++) v.setUint8(ptr + i, 0)
            v.setUint8(ptr, 2)
            return 0
        },
        fd_prestat_get() { return 8 },
        environ_sizes_get(count, size) { const v = view(); v.setUint32(count, 0, true); v.setUint32(size, 0, true); return 0 },
        environ_get() { return 0 },
        args_sizes_get(count, size) { const v = view(); v.setUint32(count, 0, true); v.setUint32(size, 0, true); return 0 },
        args_get() { return 0 },
        clock_time_get(id, precision, ptr) {
            const ms = id === 1 && typeof performance !== 'undefined' ? performance.now() : Date.now()
            view().setBigUint64(ptr, BigInt(Math.round(ms * 1e6)), true)
            return 0
        },
        random_get(ptr, n) { crypto.getRandomValues(new Uint8Array(getMemory().buffer, ptr, n)); return 0 },
        proc_exit(code) { throw new MiniError('the module called exit(' + code + ')') },
        sched_yield() { return 0 },
        poll_oneoff() { return ENOSYS },
        __enosys() { return ENOSYS },
    }
}

async function compile(source) {
    if (source instanceof WebAssembly.Module) return source
    if (source === undefined) source = new URL(WASM_FILE, import.meta.url)
    if (source instanceof ArrayBuffer || ArrayBuffer.isView(source)) return WebAssembly.compile(source)
    if (typeof Response !== 'undefined' && (source instanceof Response || source instanceof Promise)) {
        const r = await source
        return r.headers.get('content-type') === 'application/wasm' ? WebAssembly.compileStreaming(r) : WebAssembly.compile(await r.arrayBuffer())
    }
    const url = source instanceof URL ? source : new URL(String(source), import.meta.url)
    if (url.protocol === 'file:') {
        const { readFile } = await import(/* @vite-ignore */ /* webpackIgnore: true */ 'node:fs/promises')
        return WebAssembly.compile(await readFile(url))
    }
    const res = await fetch(url)
    if (!res.ok) throw new Error('cannot load ' + url + ': ' + res.status)
    return res.headers.get('content-type') === 'application/wasm' ? WebAssembly.compileStreaming(res) : WebAssembly.compile(await res.arrayBuffer())
}

async function instantiate(source, options) {
    const module = await compile(source)
    let memory = null
    const w = wasi(() => memory, options)
    const imports = {}
    for (const i of WebAssembly.Module.imports(module)) {
        const f = i.module === 'wasi_snapshot_preview1' ? (w[i.name] || w.__enosys) : () => { throw new MiniError('unexpected import ' + i.module + '.' + i.name) }
        ;(imports[i.module] = imports[i.module] || {})[i.name] = f
    }
    const instance = await WebAssembly.instantiate(module, imports)
    const x = instance.exports
    memory = x.memory
    if (x._initialize) x._initialize()
    x.mini_init()
    check(x)
    return x
}
"""
}
