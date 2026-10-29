/*
 * The LLVM C API, second part: verifier (Analysis.h), bitcode writer (BitWriter.h), targets and target machines (Target.h,
 * TargetMachine.h) and the new pass manager (Transforms/PassBuilder.h). See Core.kt for the conventions.
 *
 * `LLVMInitializeNativeTarget` & co. are `static inline` functions of Target.h (not symbols of the library), so the per-target
 * initializers are bound directly; [initNativeTarget] picks the host's.
 */
@file:CInclude("llvm-c/Analysis.h")
@file:CInclude("llvm-c/BitWriter.h")
@file:CInclude("llvm-c/Target.h")
@file:CInclude("llvm-c/TargetMachine.h")
@file:CInclude("llvm-c/Transforms/PassBuilder.h")

package mini.llvm

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.memory.Owned

typealias LLVMTargetRef = COpaquePointer
typealias LLVMTargetMachineRef = COpaquePointer
typealias LLVMTargetDataRef = COpaquePointer
typealias LLVMPassBuilderOptionsRef = COpaquePointer

const val LLVMAbortProcessAction = 0
const val LLVMPrintMessageAction = 1
const val LLVMReturnStatusAction = 2

const val LLVMCodeGenLevelNone = 0
const val LLVMCodeGenLevelLess = 1
const val LLVMCodeGenLevelDefault = 2
const val LLVMCodeGenLevelAggressive = 3
const val LLVMRelocDefault = 0
const val LLVMRelocStatic = 1
const val LLVMRelocPIC = 2
const val LLVMCodeModelDefault = 0
const val LLVMAssemblyFile = 0
const val LLVMObjectFile = 1

// ---- Analysis --------------------------------------------------------------------------------------------------------

/** Returns 1 if the module is broken (the message, to be freed with LLVMDisposeMessage, then goes to [outMessage]). */
@CName("LLVMVerifyModule") external fun LLVMVerifyModule(m: LLVMModuleRef, action: Int, outMessage: CPointer<CPointerVar<ByteVar>>?): Int
@CName("LLVMVerifyFunction") external fun LLVMVerifyFunction(fn: LLVMValueRef, action: Int): Int

// ---- BitWriter -----------------------------------------------------------------------------------------------------------

@CName("LLVMWriteBitcodeToFile") external fun LLVMWriteBitcodeToFile(m: LLVMModuleRef, path: CPointer<ByteVar>): Int
@CName("LLVMWriteBitcodeToMemoryBuffer") external fun LLVMWriteBitcodeToMemoryBuffer(m: LLVMModuleRef): LLVMMemoryBufferRef

// ---- Targets -----------------------------------------------------------------------------------------------------------------

@CName("LLVMInitializeX86TargetInfo") external fun LLVMInitializeX86TargetInfo()
@CName("LLVMInitializeX86Target") external fun LLVMInitializeX86Target()
@CName("LLVMInitializeX86TargetMC") external fun LLVMInitializeX86TargetMC()
@CName("LLVMInitializeX86AsmPrinter") external fun LLVMInitializeX86AsmPrinter()
@CName("LLVMInitializeAArch64TargetInfo") external fun LLVMInitializeAArch64TargetInfo()
@CName("LLVMInitializeAArch64Target") external fun LLVMInitializeAArch64Target()
@CName("LLVMInitializeAArch64TargetMC") external fun LLVMInitializeAArch64TargetMC()
@CName("LLVMInitializeAArch64AsmPrinter") external fun LLVMInitializeAArch64AsmPrinter()

@Owned(free = "LLVMDisposeMessage") @CName("LLVMGetDefaultTargetTriple") external fun LLVMGetDefaultTargetTriple(): CPointer<ByteVar>
@Owned(free = "LLVMDisposeMessage") @CName("LLVMGetHostCPUName") external fun LLVMGetHostCPUName(): CPointer<ByteVar>
@Owned(free = "LLVMDisposeMessage") @CName("LLVMGetHostCPUFeatures") external fun LLVMGetHostCPUFeatures(): CPointer<ByteVar>
@CName("LLVMGetTargetFromTriple") external fun LLVMGetTargetFromTriple(triple: CPointer<ByteVar>, t: CPointer<COpaquePointerVar>, errorMessage: CPointer<CPointerVar<ByteVar>>?): Int

@Owned(free = "LLVMDisposeTargetMachine") @CName("LLVMCreateTargetMachine")
external fun LLVMCreateTargetMachine(t: LLVMTargetRef, triple: CPointer<ByteVar>, cpu: CPointer<ByteVar>, features: CPointer<ByteVar>, level: Int, reloc: Int, codeModel: Int): LLVMTargetMachineRef
@CName("LLVMDisposeTargetMachine") external fun LLVMDisposeTargetMachine(t: LLVMTargetMachineRef)
@Owned(free = "LLVMDisposeTargetData") @CName("LLVMCreateTargetDataLayout") external fun LLVMCreateTargetDataLayout(t: LLVMTargetMachineRef): LLVMTargetDataRef
@CName("LLVMDisposeTargetData") external fun LLVMDisposeTargetData(td: LLVMTargetDataRef)
@CName("LLVMSetModuleDataLayout") external fun LLVMSetModuleDataLayout(m: LLVMModuleRef, dl: LLVMTargetDataRef)
@CName("LLVMTargetMachineEmitToFile")
external fun LLVMTargetMachineEmitToFile(t: LLVMTargetMachineRef, m: LLVMModuleRef, filename: CPointer<ByteVar>, codegen: Int, errorMessage: CPointer<CPointerVar<ByteVar>>?): Int
@CName("LLVMTargetMachineEmitToMemoryBuffer")
external fun LLVMTargetMachineEmitToMemoryBuffer(t: LLVMTargetMachineRef, m: LLVMModuleRef, codegen: Int, errorMessage: CPointer<CPointerVar<ByteVar>>?, outMemBuf: CPointer<COpaquePointerVar>): Int

// ---- the new pass manager ----------------------------------------------------------------------------------------------------

@Owned(free = "LLVMDisposePassBuilderOptions") @CName("LLVMCreatePassBuilderOptions") external fun LLVMCreatePassBuilderOptions(): LLVMPassBuilderOptionsRef
@CName("LLVMDisposePassBuilderOptions") external fun LLVMDisposePassBuilderOptions(options: LLVMPassBuilderOptionsRef)
@CName("LLVMPassBuilderOptionsSetVerifyEach") external fun LLVMPassBuilderOptionsSetVerifyEach(options: LLVMPassBuilderOptionsRef, verifyEach: Int)

/** `passes` is an `opt -passes=` string (`default<O1>`, `mem2reg,instcombine`). The result is NULL on success. */
@CName("LLVMRunPasses") external fun LLVMRunPasses(m: LLVMModuleRef, passes: CPointer<ByteVar>, tm: LLVMTargetMachineRef?, options: LLVMPassBuilderOptionsRef): LLVMErrorRef?
