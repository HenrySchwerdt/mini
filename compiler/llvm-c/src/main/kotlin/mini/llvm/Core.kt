/*
 * The LLVM C API (llvm-c/Core.h, LLVM 18) bound with Mini's own C interop (docs/plan-kotlin.md section 10g, phase 2): `external fun` with
 * `@CName`, no wrapper layer, no runtime shim. Every function is a direct call of the C symbol.
 *
 * Conventions: LLVM's opaque handles (`LLVMValueRef`, ...) are `COpaquePointer`s under their C names (a typealias: pointers are plain
 * machine addresses, never objects). `const char *` is `CPointer<ByteVar>`, `LLVMBool` and C `int` / `unsigned` are `Int` (an
 * `unsigned` that is a big mask, like LLVMAttributeFunctionIndex = ~0U, is the negative `Int` of the same bits), `uint64_t` and `size_t` are
 * `Long`. Arrays of handles are `CPointer<COpaquePointerVar>`. Handles that LLVM hands out and that the caller must dispose of carry
 * `@Owned(free = "LLVMDispose...")`, so the memory checker (Strict code) requires the matching call.
 *
 * Not covered: debug info metadata (DIBuilder), the deprecated typed-pointer functions, vectors, atomics, inline asm, comdats, the
 * disassembler. The module list is in compiler/README.md ("LLVM C API binding").
 */
@file:CInclude("llvm-c/Core.h")
@file:CInclude("llvm-c/Types.h")
@file:CInclude("llvm-c/Support.h")
@file:CInclude("llvm-c/Error.h")
@file:CLink("LLVM-18")

package mini.llvm

import kotlin.native.CName
import kotlinx.cinterop.*
import mini.memory.Owned

typealias LLVMContextRef = COpaquePointer
typealias LLVMModuleRef = COpaquePointer
typealias LLVMTypeRef = COpaquePointer
typealias LLVMValueRef = COpaquePointer
typealias LLVMBasicBlockRef = COpaquePointer
typealias LLVMBuilderRef = COpaquePointer
typealias LLVMAttributeRef = COpaquePointer
typealias LLVMMemoryBufferRef = COpaquePointer
typealias LLVMErrorRef = COpaquePointer

// ---- enums of the C API (C `enum` values are `int`) ---------------------------------------------

const val LLVMExternalLinkage = 0
const val LLVMPrivateLinkage = 9
const val LLVMInternalLinkage = 8

const val LLVMNoUnnamedAddr = 0
const val LLVMGlobalUnnamedAddr = 1

const val LLVMIntEQ = 32
const val LLVMIntNE = 33
const val LLVMIntUGT = 34
const val LLVMIntUGE = 35
const val LLVMIntULT = 36
const val LLVMIntULE = 37
const val LLVMIntSGT = 38
const val LLVMIntSGE = 39
const val LLVMIntSLT = 40
const val LLVMIntSLE = 41

const val LLVMRealOEQ = 1
const val LLVMRealOGT = 2
const val LLVMRealOGE = 3
const val LLVMRealOLT = 4
const val LLVMRealOLE = 5
const val LLVMRealUNE = 14

const val LLVMAttributeReturnIndex = 0
const val LLVMAttributeFirstArgIndex = 1
const val LLVMAttributeFunctionIndex = -1

// ---- memory management of the C API --------------------------------------------------------------

@CName("LLVMDisposeMessage") external fun LLVMDisposeMessage(message: CPointer<ByteVar>)
@CName("LLVMShutdown") external fun LLVMShutdown()

// ---- contexts and modules -------------------------------------------------------------------------

@Owned(free = "LLVMContextDispose") @CName("LLVMContextCreate") external fun LLVMContextCreate(): LLVMContextRef
@CName("LLVMContextDispose") external fun LLVMContextDispose(c: LLVMContextRef)
@CName("LLVMGetMDKindIDInContext") external fun LLVMGetMDKindIDInContext(c: LLVMContextRef, name: CPointer<ByteVar>, slen: Int): Int

@Owned(free = "LLVMDisposeModule") @CName("LLVMModuleCreateWithNameInContext") external fun LLVMModuleCreateWithNameInContext(moduleId: CPointer<ByteVar>, c: LLVMContextRef): LLVMModuleRef
@CName("LLVMDisposeModule") external fun LLVMDisposeModule(m: LLVMModuleRef)
@CName("LLVMSetSourceFileName") external fun LLVMSetSourceFileName(m: LLVMModuleRef, name: CPointer<ByteVar>, len: Long)
@CName("LLVMSetTarget") external fun LLVMSetTarget(m: LLVMModuleRef, triple: CPointer<ByteVar>)
@CName("LLVMGetTarget") external fun LLVMGetTarget(m: LLVMModuleRef): CPointer<ByteVar>
@CName("LLVMSetDataLayout") external fun LLVMSetDataLayout(m: LLVMModuleRef, layoutStr: CPointer<ByteVar>)
@Owned(free = "LLVMDisposeMessage") @CName("LLVMPrintModuleToString") external fun LLVMPrintModuleToString(m: LLVMModuleRef): CPointer<ByteVar>
@CName("LLVMPrintModuleToFile") external fun LLVMPrintModuleToFile(m: LLVMModuleRef, filename: CPointer<ByteVar>, errorMessage: CPointer<CPointerVar<ByteVar>>?): Int
@CName("LLVMDumpModule") external fun LLVMDumpModule(m: LLVMModuleRef)
@CName("LLVMGetNamedFunction") external fun LLVMGetNamedFunction(m: LLVMModuleRef, name: CPointer<ByteVar>): LLVMValueRef?
@CName("LLVMGetNamedGlobal") external fun LLVMGetNamedGlobal(m: LLVMModuleRef, name: CPointer<ByteVar>): LLVMValueRef?
@CName("LLVMAddFunction") external fun LLVMAddFunction(m: LLVMModuleRef, name: CPointer<ByteVar>, functionTy: LLVMTypeRef): LLVMValueRef
@CName("LLVMAddGlobal") external fun LLVMAddGlobal(m: LLVMModuleRef, ty: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef

// ---- types -------------------------------------------------------------------------------------------

@CName("LLVMInt1TypeInContext") external fun LLVMInt1TypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMInt8TypeInContext") external fun LLVMInt8TypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMInt16TypeInContext") external fun LLVMInt16TypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMInt32TypeInContext") external fun LLVMInt32TypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMInt64TypeInContext") external fun LLVMInt64TypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMFloatTypeInContext") external fun LLVMFloatTypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMDoubleTypeInContext") external fun LLVMDoubleTypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMVoidTypeInContext") external fun LLVMVoidTypeInContext(c: LLVMContextRef): LLVMTypeRef
@CName("LLVMPointerTypeInContext") external fun LLVMPointerTypeInContext(c: LLVMContextRef, addressSpace: Int): LLVMTypeRef
@CName("LLVMFunctionType") external fun LLVMFunctionType(returnType: LLVMTypeRef, paramTypes: CPointer<COpaquePointerVar>?, paramCount: Int, isVarArg: Int): LLVMTypeRef
@CName("LLVMArrayType2") external fun LLVMArrayType2(elementType: LLVMTypeRef, elementCount: Long): LLVMTypeRef
@CName("LLVMStructTypeInContext") external fun LLVMStructTypeInContext(c: LLVMContextRef, elementTypes: CPointer<COpaquePointerVar>?, elementCount: Int, packed: Int): LLVMTypeRef
@CName("LLVMStructCreateNamed") external fun LLVMStructCreateNamed(c: LLVMContextRef, name: CPointer<ByteVar>): LLVMTypeRef
@CName("LLVMStructSetBody") external fun LLVMStructSetBody(structTy: LLVMTypeRef, elementTypes: CPointer<COpaquePointerVar>?, elementCount: Int, packed: Int)
@CName("LLVMTypeOf") external fun LLVMTypeOf(v: LLVMValueRef): LLVMTypeRef
@CName("LLVMGetTypeByName2") external fun LLVMGetTypeByName2(c: LLVMContextRef, name: CPointer<ByteVar>): LLVMTypeRef?
@Owned(free = "LLVMDisposeMessage") @CName("LLVMPrintTypeToString") external fun LLVMPrintTypeToString(ty: LLVMTypeRef): CPointer<ByteVar>

// ---- values and constants -----------------------------------------------------------------------------

@Owned(free = "LLVMDisposeMessage") @CName("LLVMPrintValueToString") external fun LLVMPrintValueToString(v: LLVMValueRef): CPointer<ByteVar>
@CName("LLVMSetValueName2") external fun LLVMSetValueName2(v: LLVMValueRef, name: CPointer<ByteVar>, nameLen: Long)
@CName("LLVMConstInt") external fun LLVMConstInt(intTy: LLVMTypeRef, n: Long, signExtend: Int): LLVMValueRef
@CName("LLVMConstReal") external fun LLVMConstReal(realTy: LLVMTypeRef, n: Double): LLVMValueRef
@CName("LLVMConstNull") external fun LLVMConstNull(ty: LLVMTypeRef): LLVMValueRef
@CName("LLVMConstPointerNull") external fun LLVMConstPointerNull(ty: LLVMTypeRef): LLVMValueRef
@CName("LLVMGetUndef") external fun LLVMGetUndef(ty: LLVMTypeRef): LLVMValueRef
@CName("LLVMConstStringInContext") external fun LLVMConstStringInContext(c: LLVMContextRef, str: CPointer<ByteVar>, length: Int, dontNullTerminate: Int): LLVMValueRef
@CName("LLVMConstArray2") external fun LLVMConstArray2(elementTy: LLVMTypeRef, constantVals: CPointer<COpaquePointerVar>?, length: Long): LLVMValueRef
@CName("LLVMConstStructInContext") external fun LLVMConstStructInContext(c: LLVMContextRef, constantVals: CPointer<COpaquePointerVar>?, count: Int, packed: Int): LLVMValueRef
@CName("LLVMConstNamedStruct") external fun LLVMConstNamedStruct(structTy: LLVMTypeRef, constantVals: CPointer<COpaquePointerVar>?, count: Int): LLVMValueRef

// ---- metadata and attributes ---------------------------------------------------------------------------

@CName("LLVMMDStringInContext") external fun LLVMMDStringInContext(c: LLVMContextRef, str: CPointer<ByteVar>, slen: Int): LLVMValueRef
@CName("LLVMMDNodeInContext") external fun LLVMMDNodeInContext(c: LLVMContextRef, vals: CPointer<COpaquePointerVar>?, count: Int): LLVMValueRef
@CName("LLVMSetMetadata") external fun LLVMSetMetadata(v: LLVMValueRef, kindId: Int, node: LLVMValueRef)
@CName("LLVMGetEnumAttributeKindForName") external fun LLVMGetEnumAttributeKindForName(name: CPointer<ByteVar>, slen: Long): Int
@CName("LLVMCreateEnumAttribute") external fun LLVMCreateEnumAttribute(c: LLVMContextRef, kindId: Int, valueAttr: Long): LLVMAttributeRef
@CName("LLVMAddAttributeAtIndex") external fun LLVMAddAttributeAtIndex(f: LLVMValueRef, idx: Int, a: LLVMAttributeRef)
@CName("LLVMAddCallSiteAttribute") external fun LLVMAddCallSiteAttribute(c: LLVMValueRef, idx: Int, a: LLVMAttributeRef)
@CName("LLVMLookupIntrinsicID") external fun LLVMLookupIntrinsicID(name: CPointer<ByteVar>, nameLen: Long): Int
@CName("LLVMGetIntrinsicDeclaration") external fun LLVMGetIntrinsicDeclaration(m: LLVMModuleRef, id: Int, paramTypes: CPointer<COpaquePointerVar>?, paramCount: Long): LLVMValueRef

// ---- globals and functions -------------------------------------------------------------------------------

@CName("LLVMSetLinkage") external fun LLVMSetLinkage(global: LLVMValueRef, linkage: Int)
@CName("LLVMSetUnnamedAddress") external fun LLVMSetUnnamedAddress(global: LLVMValueRef, unnamedAddr: Int)
@CName("LLVMSetAlignment") external fun LLVMSetAlignment(v: LLVMValueRef, bytes: Int)
@CName("LLVMSetInitializer") external fun LLVMSetInitializer(globalVar: LLVMValueRef, constantVal: LLVMValueRef)
@CName("LLVMGetInitializer") external fun LLVMGetInitializer(globalVar: LLVMValueRef): LLVMValueRef?
@CName("LLVMSetGlobalConstant") external fun LLVMSetGlobalConstant(globalVar: LLVMValueRef, isConstant: Int)
@CName("LLVMSetThreadLocal") external fun LLVMSetThreadLocal(globalVar: LLVMValueRef, isThreadLocal: Int)
@CName("LLVMGlobalGetValueType") external fun LLVMGlobalGetValueType(global: LLVMValueRef): LLVMTypeRef
@CName("LLVMIsDeclaration") external fun LLVMIsDeclaration(global: LLVMValueRef): Int
@CName("LLVMGetParam") external fun LLVMGetParam(fn: LLVMValueRef, index: Int): LLVMValueRef
@CName("LLVMCountBasicBlocks") external fun LLVMCountBasicBlocks(fn: LLVMValueRef): Int
@CName("LLVMGetEntryBasicBlock") external fun LLVMGetEntryBasicBlock(fn: LLVMValueRef): LLVMBasicBlockRef

// ---- basic blocks and instructions -----------------------------------------------------------------------------

@CName("LLVMAppendBasicBlockInContext") external fun LLVMAppendBasicBlockInContext(c: LLVMContextRef, fn: LLVMValueRef, name: CPointer<ByteVar>): LLVMBasicBlockRef
@CName("LLVMCreateBasicBlockInContext") external fun LLVMCreateBasicBlockInContext(c: LLVMContextRef, name: CPointer<ByteVar>): LLVMBasicBlockRef
@CName("LLVMAppendExistingBasicBlock") external fun LLVMAppendExistingBasicBlock(fn: LLVMValueRef, bb: LLVMBasicBlockRef)
@CName("LLVMGetFirstInstruction") external fun LLVMGetFirstInstruction(bb: LLVMBasicBlockRef): LLVMValueRef?
@CName("LLVMGetBasicBlockTerminator") external fun LLVMGetBasicBlockTerminator(bb: LLVMBasicBlockRef): LLVMValueRef?
@CName("LLVMDeleteBasicBlock") external fun LLVMDeleteBasicBlock(bb: LLVMBasicBlockRef)
@CName("LLVMInstructionEraseFromParent") external fun LLVMInstructionEraseFromParent(inst: LLVMValueRef)

// ---- the instruction builder -------------------------------------------------------------------------------------

@Owned(free = "LLVMDisposeBuilder") @CName("LLVMCreateBuilderInContext") external fun LLVMCreateBuilderInContext(c: LLVMContextRef): LLVMBuilderRef
@CName("LLVMDisposeBuilder") external fun LLVMDisposeBuilder(builder: LLVMBuilderRef)
@CName("LLVMPositionBuilderBefore") external fun LLVMPositionBuilderBefore(builder: LLVMBuilderRef, instr: LLVMValueRef)
@CName("LLVMPositionBuilderAtEnd") external fun LLVMPositionBuilderAtEnd(builder: LLVMBuilderRef, block: LLVMBasicBlockRef)
@CName("LLVMGetInsertBlock") external fun LLVMGetInsertBlock(builder: LLVMBuilderRef): LLVMBasicBlockRef

@CName("LLVMBuildRetVoid") external fun LLVMBuildRetVoid(b: LLVMBuilderRef): LLVMValueRef
@CName("LLVMBuildRet") external fun LLVMBuildRet(b: LLVMBuilderRef, v: LLVMValueRef): LLVMValueRef
@CName("LLVMBuildBr") external fun LLVMBuildBr(b: LLVMBuilderRef, dest: LLVMBasicBlockRef): LLVMValueRef
@CName("LLVMBuildCondBr") external fun LLVMBuildCondBr(b: LLVMBuilderRef, ifCond: LLVMValueRef, then: LLVMBasicBlockRef, els: LLVMBasicBlockRef): LLVMValueRef
@CName("LLVMBuildUnreachable") external fun LLVMBuildUnreachable(b: LLVMBuilderRef): LLVMValueRef

@CName("LLVMBuildAdd") external fun LLVMBuildAdd(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildSub") external fun LLVMBuildSub(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildMul") external fun LLVMBuildMul(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildSDiv") external fun LLVMBuildSDiv(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildSRem") external fun LLVMBuildSRem(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildAnd") external fun LLVMBuildAnd(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildOr") external fun LLVMBuildOr(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildXor") external fun LLVMBuildXor(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildShl") external fun LLVMBuildShl(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildAShr") external fun LLVMBuildAShr(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildLShr") external fun LLVMBuildLShr(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFAdd") external fun LLVMBuildFAdd(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFSub") external fun LLVMBuildFSub(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFMul") external fun LLVMBuildFMul(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFDiv") external fun LLVMBuildFDiv(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFRem") external fun LLVMBuildFRem(b: LLVMBuilderRef, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFNeg") external fun LLVMBuildFNeg(b: LLVMBuilderRef, v: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef

@CName("LLVMBuildAlloca") external fun LLVMBuildAlloca(b: LLVMBuilderRef, ty: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildLoad2") external fun LLVMBuildLoad2(b: LLVMBuilderRef, ty: LLVMTypeRef, pointerVal: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildStore") external fun LLVMBuildStore(b: LLVMBuilderRef, v: LLVMValueRef, ptr: LLVMValueRef): LLVMValueRef
@CName("LLVMBuildGEP2") external fun LLVMBuildGEP2(b: LLVMBuilderRef, ty: LLVMTypeRef, pointer: LLVMValueRef, indices: CPointer<COpaquePointerVar>?, numIndices: Int, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildInBoundsGEP2") external fun LLVMBuildInBoundsGEP2(b: LLVMBuilderRef, ty: LLVMTypeRef, pointer: LLVMValueRef, indices: CPointer<COpaquePointerVar>?, numIndices: Int, name: CPointer<ByteVar>): LLVMValueRef

@CName("LLVMBuildTrunc") external fun LLVMBuildTrunc(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildZExt") external fun LLVMBuildZExt(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildSExt") external fun LLVMBuildSExt(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildSIToFP") external fun LLVMBuildSIToFP(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildUIToFP") external fun LLVMBuildUIToFP(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFPTrunc") external fun LLVMBuildFPTrunc(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFPExt") external fun LLVMBuildFPExt(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildPtrToInt") external fun LLVMBuildPtrToInt(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildIntToPtr") external fun LLVMBuildIntToPtr(b: LLVMBuilderRef, v: LLVMValueRef, destTy: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef

@CName("LLVMBuildICmp") external fun LLVMBuildICmp(b: LLVMBuilderRef, op: Int, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFCmp") external fun LLVMBuildFCmp(b: LLVMBuilderRef, op: Int, lhs: LLVMValueRef, rhs: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildPhi") external fun LLVMBuildPhi(b: LLVMBuilderRef, ty: LLVMTypeRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMAddIncoming") external fun LLVMAddIncoming(phiNode: LLVMValueRef, incomingValues: CPointer<COpaquePointerVar>?, incomingBlocks: CPointer<COpaquePointerVar>?, count: Int)
@CName("LLVMBuildSelect") external fun LLVMBuildSelect(b: LLVMBuilderRef, ifv: LLVMValueRef, then: LLVMValueRef, els: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildCall2") external fun LLVMBuildCall2(b: LLVMBuilderRef, ty: LLVMTypeRef, fn: LLVMValueRef, args: CPointer<COpaquePointerVar>?, numArgs: Int, name: CPointer<ByteVar>): LLVMValueRef
@CName("LLVMBuildFreeze") external fun LLVMBuildFreeze(b: LLVMBuilderRef, v: LLVMValueRef, name: CPointer<ByteVar>): LLVMValueRef

// ---- errors (LLVMErrorRef: NULL is success) -------------------------------------------------------------------------

@Owned(free = "LLVMDisposeErrorMessage") @CName("LLVMGetErrorMessage") external fun LLVMGetErrorMessage(err: LLVMErrorRef): CPointer<ByteVar>
@CName("LLVMDisposeErrorMessage") external fun LLVMDisposeErrorMessage(errMsg: CPointer<ByteVar>)
@CName("LLVMConsumeError") external fun LLVMConsumeError(err: LLVMErrorRef)


// ---- memory buffers ------------------------------------------------------------------------------------------------

@CName("LLVMGetBufferStart") external fun LLVMGetBufferStart(memBuf: LLVMMemoryBufferRef): CPointer<ByteVar>
@CName("LLVMGetBufferSize") external fun LLVMGetBufferSize(memBuf: LLVMMemoryBufferRef): Long
@CName("LLVMDisposeMemoryBuffer") external fun LLVMDisposeMemoryBuffer(memBuf: LLVMMemoryBufferRef)
