/*
 * The LLVM C API, third part: the ORC JIT (Orc.h, LLJIT.h, OrcEE.h): `LLVMOrcCreateLLJIT`, modules added as thread-safe modules,
 * symbol lookup by name. See Core.kt for the conventions.
 */
@file:CInclude("llvm-c/Orc.h")
@file:CInclude("llvm-c/LLJIT.h")

package mini.llvm

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.memory.Owned

typealias LLVMOrcLLJITRef = COpaquePointer
typealias LLVMOrcLLJITBuilderRef = COpaquePointer
typealias LLVMOrcJITDylibRef = COpaquePointer
typealias LLVMOrcThreadSafeContextRef = COpaquePointer
typealias LLVMOrcThreadSafeModuleRef = COpaquePointer
typealias LLVMOrcDefinitionGeneratorRef = COpaquePointer
typealias LLVMOrcResourceTrackerRef = COpaquePointer

@CName("LLVMOrcCreateLLJITBuilder") external fun LLVMOrcCreateLLJITBuilder(): LLVMOrcLLJITBuilderRef
/** Takes the builder; the JIT is written to [result]. NULL on success. */
@CName("LLVMOrcCreateLLJIT") external fun LLVMOrcCreateLLJIT(result: CPointer<COpaquePointerVar>, builder: LLVMOrcLLJITBuilderRef?): LLVMErrorRef?
@CName("LLVMOrcDisposeLLJIT") external fun LLVMOrcDisposeLLJIT(j: LLVMOrcLLJITRef): LLVMErrorRef?
@CName("LLVMOrcLLJITGetMainJITDylib") external fun LLVMOrcLLJITGetMainJITDylib(j: LLVMOrcLLJITRef): LLVMOrcJITDylibRef
@CName("LLVMOrcLLJITGetGlobalPrefix") external fun LLVMOrcLLJITGetGlobalPrefix(j: LLVMOrcLLJITRef): Byte
@CName("LLVMOrcLLJITAddLLVMIRModule") external fun LLVMOrcLLJITAddLLVMIRModule(j: LLVMOrcLLJITRef, jd: LLVMOrcJITDylibRef, tsm: LLVMOrcThreadSafeModuleRef): LLVMErrorRef?
@CName("LLVMOrcLLJITAddLLVMIRModuleWithRT") external fun LLVMOrcLLJITAddLLVMIRModuleWithRT(j: LLVMOrcLLJITRef, tracker: LLVMOrcResourceTrackerRef, tsm: LLVMOrcThreadSafeModuleRef): LLVMErrorRef?
/** Looks [name] up (initializing the JIT'd code on first use); the address of the symbol is written to [result]. */
@CName("LLVMOrcLLJITLookup") external fun LLVMOrcLLJITLookup(j: LLVMOrcLLJITRef, result: CPointer<LongVar>, name: CPointer<ByteVar>): LLVMErrorRef?

@Owned(free = "LLVMOrcDisposeThreadSafeContext") @CName("LLVMOrcCreateNewThreadSafeContext") external fun LLVMOrcCreateNewThreadSafeContext(): LLVMOrcThreadSafeContextRef
@CName("LLVMOrcDisposeThreadSafeContext") external fun LLVMOrcDisposeThreadSafeContext(tsCtx: LLVMOrcThreadSafeContextRef)
/** Takes the module (and, with the JIT, the context lives on in [tsCtx]). */
@CName("LLVMOrcCreateNewThreadSafeModule") external fun LLVMOrcCreateNewThreadSafeModule(m: LLVMModuleRef, tsCtx: LLVMOrcThreadSafeContextRef): LLVMOrcThreadSafeModuleRef

@CName("LLVMOrcCreateDynamicLibrarySearchGeneratorForProcess")
external fun LLVMOrcCreateDynamicLibrarySearchGeneratorForProcess(result: CPointer<COpaquePointerVar>, globalPrefix: Byte, filter: COpaquePointer?, filterCtx: COpaquePointer?): LLVMErrorRef?
@CName("LLVMOrcCreateDynamicLibrarySearchGeneratorForPath")
external fun LLVMOrcCreateDynamicLibrarySearchGeneratorForPath(result: CPointer<COpaquePointerVar>, fileName: CPointer<ByteVar>, globalPrefix: Byte, filter: COpaquePointer?, filterCtx: COpaquePointer?): LLVMErrorRef?
@CName("LLVMOrcJITDylibAddGenerator") external fun LLVMOrcJITDylibAddGenerator(jd: LLVMOrcJITDylibRef, dg: LLVMOrcDefinitionGeneratorRef)
@CName("LLVMOrcJITDylibCreateResourceTracker") external fun LLVMOrcJITDylibCreateResourceTracker(jd: LLVMOrcJITDylibRef): LLVMOrcResourceTrackerRef
@CName("LLVMOrcResourceTrackerRemove") external fun LLVMOrcResourceTrackerRemove(tracker: LLVMOrcResourceTrackerRef): LLVMErrorRef?
@CName("LLVMOrcReleaseResourceTracker") external fun LLVMOrcReleaseResourceTracker(tracker: LLVMOrcResourceTrackerRef)
