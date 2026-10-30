@file:CInclude("time.h")
@file:CInclude("unistd.h")
@file:Suppress("WRONG_MODIFIER_TARGET")

package mini.llvmapi

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.backend.MirModule
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
 * The ORC JIT (docs/plan-kotlin.md section 10g): the module is built in memory by [LlvmApiEmitter] with the same lowering as the object
 * file path, handed to an LLJIT and `mk_main` is called in this process. No file is written, nothing is linked.
 *
 * The runtime is the one this compiler binary contains (libminirt is linked into every Mini program, and the compiler is one):
 * JIT'd code finds the `mk_*` functions and type infos through the process symbol table (the compiler is linked with `-rdynamic`).
 * The thread-local `mk_pending_exception` cannot be referenced by JIT'd code (the ORC loader has no TLS relocations for it), so
 * the module is generated with [mini.backend.llvm.LlvmTarget.pendingAccessor]: the code calls the runtime's `mk_pending_slot()`.
 */
object LlvmJit {
    private fun fail(what: String, err: COpaquePointer?) {
        val text = LlvmApiEmitter.errorText(err)
        if (text != null) throw IllegalStateException("$what: $text")
    }

    /** Compiles and runs [module]; returns the exit status (0: `mk_main` returned; an uncaught exception ends the process like in a binary). */
    fun run(module: MirModule, programArgs: List<String>, optLevel: Int, rcOpt: Boolean, timing: Boolean): Int {
        val t0 = nowNanos()
        val machine = LlvmApiEmitter.createMachine(optLevel)
        val tsc = LLVMOrcCreateNewThreadSafeContext()
        val context = LLVMOrcThreadSafeContextGetContext(tsc)
        val mod = LlvmApiEmitter.buildModule(module, context, machine, rcOpt, true, true, false, true)
        val t1 = nowNanos()
        LlvmApiEmitter.optimize(mod, machine, optLevel)
        val t2 = nowNanos()
        val tsm = LLVMOrcCreateNewThreadSafeModule(mod, tsc)
        val entry: Long = memScoped {
            val jitp = alloc<COpaquePointerVar>()
            fail("cannot create the JIT", LLVMOrcCreateLLJIT(jitp.ptr, null))
            val jit = jitp.value
            if (jit == null) throw IllegalStateException("cannot create the JIT")
            val jd = LLVMOrcLLJITGetMainJITDylib(jit)
            val gen = alloc<COpaquePointerVar>()
            fail("cannot reflect the process symbols", LLVMOrcCreateDynamicLibrarySearchGeneratorForProcess(gen.ptr, LLVMOrcLLJITGetGlobalPrefix(jit), null, null))
            val g = gen.value
            if (g == null) throw IllegalStateException("cannot reflect the process symbols")
            LLVMOrcJITDylibAddGenerator(jd, g)
            fail("cannot add the module to the JIT", LLVMOrcLLJITAddLLVMIRModule(jit, jd, tsm))
            val addr = alloc<LongVar>()
            fail("cannot compile the module", LLVMOrcLLJITLookup(jit, addr.ptr, "mk_main".cstr.ptr))
            addr.value
        }
        val t3 = nowNanos()
        val program = module.sourceName
        setProgramArgs(program, programArgs)
        val main = entry.toCPointer<CFunction<() -> Unit>>()
        if (main == null) throw IllegalStateException("no entry point")
        main()
        mkRuntimeFlush()
        val t4 = nowNanos()
        if (timing) {
            eprintln("jit: build module " + ms(t1 - t0) + " ms, optimize " + ms(t2 - t1) + " ms, machine code " + ms(t3 - t2) + " ms, run " + ms(t4 - t3) + " ms, total " + ms(t4 - t0) + " ms")
        }
        // the JIT and its code stay until the process ends (the compiler exits right after the run)
        LLVMOrcDisposeThreadSafeContext(tsc)
        return 0
    }

    private fun ms(nanos: Long): String {
        val tenth = nanos / 100000L
        return (tenth / 10L).toString() + "." + (tenth % 10L).toString()
    }

    /** `main(args)` reads the process's arguments through the runtime: [program] and [args] replace the compiler's own. */
    private fun setProgramArgs(program: String, args: List<String>) {
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
