package mini.llvmapi

import kotlinx.cinterop.*
import mini.backend.MirModule
import mini.backend.llvm.LlvmLowering
import mini.backend.llvm.LlvmTarget
import mini.io.writeTextFile
import mini.llvm.*

/**
 * MIR to machine code through the LLVM C API (docs/plan-kotlin.md section 10g, phase 2): the same lowering as the text backend
 * ([LlvmLowering]), but the module is built in memory ([ApiIrBuilder]), verified, optimized with the new pass manager
 * (`default<O1>`...) and written as an object file by the target machine; or handed to the ORC JIT ([LlvmJit]).
 */
object LlvmApiEmitter {
    private var targetsReady = false

    /** Registers the host's target (LLVMInitializeNativeTarget is an inline function of the header, not a library symbol). */
    fun initNativeTarget() {
        if (targetsReady) return
        val triple = defaultTriple()
        if (triple.startsWith("aarch64") || triple.startsWith("arm64")) {
            LLVMInitializeAArch64TargetInfo()
            LLVMInitializeAArch64Target()
            LLVMInitializeAArch64TargetMC()
            LLVMInitializeAArch64AsmPrinter()
        } else if (triple.startsWith("x86_64") || triple.startsWith("i686")) {
            LLVMInitializeX86TargetInfo()
            LLVMInitializeX86Target()
            LLVMInitializeX86TargetMC()
            LLVMInitializeX86AsmPrinter()
        } else {
            throw IllegalStateException("no LLVM target initializer for the host triple $triple")
        }
        targetsReady = true
    }

    fun defaultTriple(): String {
        val s = LLVMGetDefaultTargetTriple()
        val text = s.toKString()
        LLVMDisposeMessage(s)
        return text
    }

    /** Takes an error message the C API allocated, frees it and returns the text. */
    fun takeMessage(p: CPointer<ByteVar>?): String {
        if (p == null) return "unknown error"
        val text = p.toKString()
        LLVMDisposeMessage(p)
        return text
    }

    /** The text of an `LLVMErrorRef` (consumed), or null for NULL = success. */
    fun errorText(err: COpaquePointer?): String? {
        if (err == null) return null
        val s = LLVMGetErrorMessage(err)
        val text = s.toKString()
        LLVMDisposeErrorMessage(s)
        return text
    }

    /** A target machine for the host (generic CPU, position independent code), `optLevel` 0..3 as in `-O<n>`. */
    fun createMachine(optLevel: Int): LLVMTargetMachineRef {
        initNativeTarget()
        val triple = defaultTriple()
        return memScoped {
            val tref = alloc<COpaquePointerVar>()
            val err = alloc<CPointerVar<ByteVar>>()
            if (LLVMGetTargetFromTriple(triple.cstr.ptr, tref.ptr, err.ptr) != 0) {
                throw IllegalStateException("no LLVM target for $triple: " + takeMessage(err.value))
            }
            val level = if (optLevel <= 0) LLVMCodeGenLevelNone else if (optLevel == 1) LLVMCodeGenLevelLess else if (optLevel == 2) LLVMCodeGenLevelDefault else LLVMCodeGenLevelAggressive
            val t = tref.value
            if (t == null) throw IllegalStateException("no LLVM target for $triple")
            LLVMCreateTargetMachine(t, triple.cstr.ptr, "generic".cstr.ptr, "".cstr.ptr, level, LLVMRelocPIC, LLVMCodeModelDefault)
        }
    }

    /** Builds, verifies and returns the LLVM module of [module] in [context] (not optimized). The caller owns the module. */
    fun buildModule(
        module: MirModule,
        context: LLVMContextRef,
        machine: LLVMTargetMachineRef,
        rcOpt: Boolean,
        strict: Boolean,
        conventions: Boolean,
        stats: Boolean,
        pendingAccessor: Boolean,
    ): LLVMModuleRef {
        val mod = memScoped { LLVMModuleCreateWithNameInContext(module.sourceName.cstr.ptr, context) }
        val dl = LLVMCreateTargetDataLayout(machine)
        LLVMSetModuleDataLayout(mod, dl)
        LLVMDisposeTargetData(dl)
        val triple = defaultTriple()
        memScoped { LLVMSetTarget(mod, triple.cstr.ptr) }
        val builder = ApiIrBuilder(context, mod)
        try {
            LlvmLowering.lower(module, builder, rcOpt, strict, conventions, stats, LlvmTarget(8, null, null, pendingAccessor))
        } catch (e: Throwable) {
            builder.dispose()
            LLVMDisposeModule(mod)
            throw e
        }
        builder.dispose()
        val problem = verify(mod)
        if (problem != null) {
            LLVMDisposeModule(mod)
            throw IllegalStateException("the generated LLVM module is invalid:\n" + problem)
        }
        return mod
    }

    /** The verifier's complaint about [mod], or null. */
    fun verify(mod: LLVMModuleRef): String? = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        if (LLVMVerifyModule(mod, LLVMReturnStatusAction, err.ptr) != 0) takeMessage(err.value) else null
    }

    fun moduleText(mod: LLVMModuleRef): String {
        val s = LLVMPrintModuleToString(mod)
        val text = s.toKString()
        LLVMDisposeMessage(s)
        return text
    }

    /** Runs the new pass manager's `default<O<n>>` pipeline (`opt -passes=`) over [mod]. */
    fun optimize(mod: LLVMModuleRef, machine: LLVMTargetMachineRef, optLevel: Int) {
        val pipeline = "default<O" + (if (optLevel < 0) 0 else if (optLevel > 3) 3 else optLevel) + ">"
        val opts = LLVMCreatePassBuilderOptions()
        val err = memScoped { LLVMRunPasses(mod, pipeline.cstr.ptr, machine, opts) }
        LLVMDisposePassBuilderOptions(opts)
        val text = errorText(err)
        if (text != null) throw IllegalStateException("LLVM passes failed: $text")
    }

    /** Writes the machine code of [mod] as an object file. */
    fun emitObject(mod: LLVMModuleRef, machine: LLVMTargetMachineRef, path: String) {
        memScoped {
            val err = alloc<CPointerVar<ByteVar>>()
            if (LLVMTargetMachineEmitToFile(machine, mod, path.cstr.ptr, LLVMObjectFile, err.ptr) != 0) {
                throw IllegalStateException("cannot write $path: " + takeMessage(err.value))
            }
        }
    }

    /**
     * MIR to an object file: build, verify, optimize, emit.
     */
    fun compileToObject(
        module: MirModule, path: String, llPath: String?, optLevel: Int, rcOpt: Boolean, strict: Boolean, conventions: Boolean, stats: Boolean,
    ) {
        val machine = createMachine(optLevel)
        val context = LLVMContextCreate()
        try {
            val mod = buildModule(module, context, machine, rcOpt, strict, conventions, stats, false)
            try {
                if (llPath != null) writeTextFile(llPath, moduleText(mod))
                optimize(mod, machine, optLevel)
                emitObject(mod, machine, path)
            } finally {
                LLVMDisposeModule(mod)
            }
        } finally {
            LLVMContextDispose(context)
            LLVMDisposeTargetMachine(machine)
        }
    }
}
