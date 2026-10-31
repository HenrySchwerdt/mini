@file:CInclude("time.h")
@file:CInclude("unistd.h")
@file:Suppress("WRONG_MODIFIER_TARGET")

package mini.llvmapi

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.backend.MirModule
import mini.backend.MirClass
import mini.backend.MirFunction
import mini.backend.RcOpt
import mini.backend.Tracking
import mini.backend.llvm.IrStrings
import mini.llvm.*

@CStruct("timespec")
external class MkTimespec : CStructVar {
    var tv_sec: Long
    var tv_nsec: Long
}

@CName("clock_gettime") external fun cClockGettime(clock: Int, ts: CPointer<MkTimespec>): Int
@CName("write") external fun cWrite(fd: Int, buf: CPointer<ByteVar>, n: Long): Long

/** The runtime this process already runs (the compiler is a Mini program): flushing its stdout buffer and setting `main`'s `args`. */
@CName("mk_runtime_flush") external fun mkRuntimeFlush()
@CName("mk_b1_set_args") external fun mkB1SetArgs(argc: Int, argv: CPointer<CPointerVar<ByteVar>>)
@CName("dlopen") external fun cDlopen(filename: CPointer<ByteVar>?, flags: Int): COpaquePointer?
@CName("dlsym") external fun cDlsym(handle: COpaquePointer?, name: CPointer<ByteVar>): COpaquePointer?

fun nowNanos(): Long = memScoped {
    val ts = alloc<MkTimespec>()
    cClockGettime(1, ts.ptr)
    ts.tv_sec * 1000000000L + ts.tv_nsec
}

fun eprintln(text: String) {
    memScoped {
        val line = text + "\n"
        cWrite(2, line.cstr.ptr, utf8Size(line))
    }
}

/** The UTF-8 length of a string (what `cstr` writes, without the NUL). */
fun utf8Size(s: String): Long = IrStrings.utf8(s).size.toLong()

/**
 * Owns an ORC LLJIT for the lifetime of a native compiler session. Modules are added to its main JITDylib and
 * remain loaded until [close]. Each module's entry point is looked up explicitly, so callers can retain the JIT
 * while adding later inputs. `mk_main` is still the conventional symbol used by [run].
 *
 * A failed build/verification happens before the module is handed to ORC and does not alter the session. Each module
 * has a resource tracker, so failed materialization removes that module's symbols before the error is returned.
 */
class LlvmJitSession(optLevel: Int) : AutoCloseable {
    private var closed = false
    private val optimizationLevel = optLevel
    private val machine = LlvmApiEmitter.createMachine(optLevel)
    private val knownFunctions = HashMap<String, MirFunction>()
    private val knownOwnedMasks = HashMap<String, BooleanArray>()
    private val knownBorrowedFunctions = HashSet<String>()
    private val knownClasses = HashSet<String>()
    private var sessionRcOpt: Boolean? = null
    private val jit: LLVMOrcLLJITRef = try {
        createJit()
    } catch (e: Throwable) {
        LLVMDisposeTargetMachine(machine)
        throw e
    }

    private fun fail(what: String, err: COpaquePointer?) {
        val text = LlvmApiEmitter.errorText(err)
        if (text != null) throw IllegalStateException("$what: $text")
    }

    private fun createJit(): LLVMOrcLLJITRef = memScoped {
        val jitp = alloc<COpaquePointerVar>()
        fail("cannot create the JIT", LLVMOrcCreateLLJIT(jitp.ptr, null))
        val result = jitp.value ?: throw IllegalStateException("cannot create the JIT")
        try {
            val gen = alloc<COpaquePointerVar>()
            fail("cannot reflect the process symbols", LLVMOrcCreateDynamicLibrarySearchGeneratorForProcess(gen.ptr, LLVMOrcLLJITGetGlobalPrefix(result), null, null))
            val generator = gen.value ?: throw IllegalStateException("cannot reflect the process symbols")
            LLVMOrcJITDylibAddGenerator(LLVMOrcLLJITGetMainJITDylib(result), generator)
            result
        } catch (e: Throwable) {
            fail("cannot dispose the JIT after initialization failed", LLVMOrcDisposeLLJIT(result))
            throw e
        }
    }

    /** Add [module] to this JIT and return the address of [entrySymbol], or null for a declaration-only module. */
    fun addModule(module: MirModule, rcOpt: Boolean, entrySymbol: String? = "mk_main"): Long? {
        check(!closed) { "the JIT session is closed" }
        val establishedRcOpt = sessionRcOpt
        require(establishedRcOpt == null || establishedRcOpt == rcOpt) {
            "the JIT session cannot change reference-count optimization mode"
        }
        val freshFunctions = module.functions.filter { it.cName == "mk_main" || !knownFunctions.containsKey(it.cName) }
        val sessionClasses = module.classes.map { c -> if (knownClasses.contains(c.cName)) externalClass(c) else c }
        val sessionModule = MirModule(module.sourceName, freshFunctions, sessionClasses, module.externs, module.includes, module.links, module.wasmExports)
        val externalSignatures = knownFunctions.values.toList()
        val externalOwnedMasks = knownOwnedMasks.mapValues { (_, mask) -> BooleanArray(mask.size) { mask[it] } }
        val externalBorrowedFunctions = knownBorrowedFunctions.toSet()
        var context: LLVMContextRef? = null
        val tsc = memScoped {
            val contextOut = alloc<COpaquePointerVar>()
            val result = newOrcContext(contextOut.ptr)
            context = contextOut.value
            result
        }
        val contextRef = context ?: throw IllegalStateException("LLVM ORC returned no context")
        var mod: LLVMModuleRef? = null
        try {
            val codeEntry = entrySymbol ?: "mk_main"
            val userMain = "mkh_user_main_" + codeEntry
            mod = LlvmApiEmitter.buildModule(sessionModule, contextRef, machine, rcOpt, true, true, false, true, true, true, codeEntry, userMain, externalSignatures, externalOwnedMasks, externalBorrowedFunctions)
            if (entrySymbol != null && memScoped { LLVMGetNamedFunction(mod!!, entrySymbol.cstr.ptr) } == null) {
                throw IllegalArgumentException("module has no mk_main entry point")
            }
            LlvmApiEmitter.optimize(mod, machine, optimizationLevel)
            val tsm = LLVMOrcCreateNewThreadSafeModule(mod, tsc)
            mod = null
            val jd = LLVMOrcLLJITGetMainJITDylib(jit)
            val tracker = LLVMOrcJITDylibCreateResourceTracker(jd)
            try {
                // The thread-safe module retains the context after this handle is disposed; the tracker owns its code and globals.
                fail("cannot add the module to the JIT", LLVMOrcLLJITAddLLVMIRModuleWithRT(jit, tracker, tsm))
                if (entrySymbol != null) {
                    val address = memScoped {
                        val out = alloc<LongVar>()
                        fail("cannot compile the module", LLVMOrcLLJITLookup(jit, out.ptr, entrySymbol.cstr.ptr))
                        out.value
                    }
                    for (f in freshFunctions) if (f.cName != "mk_main") knownFunctions[f.cName] = f
                    rememberOwnedMasks(sessionModule, freshFunctions, rcOpt, externalSignatures, externalOwnedMasks, externalBorrowedFunctions)
                    for (c in module.classes) knownClasses.add(c.cName)
                    sessionRcOpt = rcOpt
                    LLVMOrcReleaseResourceTracker(tracker)
                    return address
                }
                for (f in freshFunctions) if (f.cName != "mk_main") knownFunctions[f.cName] = f
                rememberOwnedMasks(sessionModule, freshFunctions, rcOpt, externalSignatures, externalOwnedMasks, externalBorrowedFunctions)
                for (c in module.classes) knownClasses.add(c.cName)
                sessionRcOpt = rcOpt
                LLVMOrcReleaseResourceTracker(tracker)
                return null
            } catch (e: Throwable) {
                // ORC may have accepted definitions before lookup discovers an unresolved symbol. Roll this input back.
                val removeError = LLVMOrcResourceTrackerRemove(tracker)
                if (removeError != null) LlvmApiEmitter.errorText(removeError)
                LLVMOrcReleaseResourceTracker(tracker)
                throw e
            }
        } finally {
            if (mod != null) LLVMDisposeModule(mod!!)
            LLVMOrcDisposeThreadSafeContext(tsc)
        }
    }

    private fun externalClass(c: MirClass): MirClass = MirClass(
        c.qualifiedName, c.cName, c.isInterface, c.superCName, c.fields, c.interfaces, c.vtable, c.itables,
        c.objectCtor, true, c.strict,
    )

    private fun rememberOwnedMasks(
        module: MirModule,
        functions: List<MirFunction>,
        rcOpt: Boolean,
        externalSignatures: List<MirFunction>,
        externalOwnedMasks: Map<String, BooleanArray>,
        externalBorrowedFunctions: Set<String>,
    ) {
        if (!rcOpt) return
        // Match the exact inputs used by emission; constructor conventions can be inferred by forwarding into prior cells.
        val plan = RcOpt.analyze(module, Tracking(module, true), true, externalSignatures, externalOwnedMasks, externalBorrowedFunctions)
        for (f in functions) {
            val mask = plan.ownedMask(f.cName) ?: continue
            knownOwnedMasks[f.cName] = BooleanArray(mask.size) { mask[it] }
        }
        for (f in functions) if (plan.returnsBorrowed(f.cName)) knownBorrowedFunctions.add(f.cName)
    }

    /** LLVM 18 has GetContext but not FromLLVMContext; newer releases have the inverse pair. */
    private fun newOrcContext(contextOut: CPointer<COpaquePointerVar>): LLVMOrcThreadSafeContextRef = memScoped {
        val handle = cDlopen(null, 1) ?: throw IllegalStateException("cannot inspect LLVM ORC context APIs")
        val fromContext = cDlsym(handle, "LLVMOrcCreateNewThreadSafeContextFromLLVMContext".cstr.ptr)
        if (fromContext != null) {
            val context = LLVMContextCreate()
            val create = fromContext.reinterpret<CFunction<(LLVMContextRef) -> LLVMOrcThreadSafeContextRef>>()
            contextOut.pointed.value = context
            return create(context)
        }
        val getContext = cDlsym(handle, "LLVMOrcThreadSafeContextGetContext".cstr.ptr)
            ?: throw IllegalStateException("LLVM ORC exposes neither supported thread-safe context API")
        val tsc = LLVMOrcCreateNewThreadSafeContext()
        val get = getContext.reinterpret<CFunction<(LLVMOrcThreadSafeContextRef) -> LLVMContextRef>>()
        contextOut.pointed.value = get(tsc)
        tsc
    }

    /** Add [module] and call its no-argument `Unit` entry point. */
    fun runModule(module: MirModule, rcOpt: Boolean, entrySymbol: String = "mk_main") {
        val address = addModule(module, rcOpt, entrySymbol)
            ?: throw IllegalStateException("no entry point")
        val entry = address.toCPointer<CFunction<() -> Unit>>()
            ?: throw IllegalStateException("no entry point")
        entry()
        mkRuntimeFlush()
    }

    /** Dispose the LLJIT and all modules it owns. Safe to call more than once. */
    override fun close() {
        if (closed) return
        closed = true
        val err = LLVMOrcDisposeLLJIT(jit)
        LLVMDisposeTargetMachine(machine)
        fail("cannot dispose the JIT", err)
    }
}

/** The ORC JIT (docs/plan-kotlin.md section 10g): lower in memory and call the generated `mk_main` in this process. */
object LlvmJit {
    /** Compiles and runs [module]; returns the exit status (0: `mk_main` returned). */
    fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean, timing: Boolean): Int {
        val t0 = nowNanos()
        val session = LlvmJitSession(optLevel)
        try {
            val t1 = nowNanos()
            val entry = session.addModule(module, rcOpt) ?: throw IllegalStateException("no entry point")
            val t2 = nowNanos()
            setProgramArgs(module.sourceName, programArgs)
            val main = entry.toCPointer<CFunction<() -> Unit>>()
            if (main == null) throw IllegalStateException("no entry point")
            main()
            mkRuntimeFlush()
            val t3 = nowNanos()
            if (timing) eprintln("jit: build module " + ms(t1 - t0) + " ms, optimize and machine code " + ms(t2 - t1) + " ms, run " + ms(t3 - t2) + " ms, total " + ms(t3 - t0) + " ms")
            return 0
        } finally {
            session.close()
        }
    }

    private fun ms(nanos: Long): String {
        val tenth = nanos / 100000L
        return (tenth / 10L).toString() + "." + (tenth % 10L)
    }

    /** `main(args)` reads the process's arguments through the runtime: [program] and [args] replace the compiler's own. */
    fun setProgramArgs(program: String, args: List<String>) {
        val argv = nativeHeap.allocArray<CPointerVar<ByteVar>>(args.size + 2)
        argv[0] = copyString(program)
        for (i in args.indices) argv[i + 1] = copyString(args[i])
        mkB1SetArgs(args.size + 1, argv)
    }

    /** A C copy of [s] that is never freed (the runtime keeps `argv`). */
    private fun copyString(s: String): CPointer<ByteVar> {
        val n = utf8Size(s)
        val p = nativeHeap.allocArray<ByteVar>(n + 1L)
        memScoped {
            val tmp = s.cstr.ptr
            for (i in 0 until n.toInt()) p[i] = tmp[i]
        }
        return p
    }
}
