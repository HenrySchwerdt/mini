package mini.llvmapi

import kotlinx.cinterop.*
import mini.backend.EmitError
import mini.backend.llvm.IrAttrs
import mini.backend.llvm.IrBuilder
import mini.backend.llvm.IrCast
import mini.backend.llvm.IrCmp
import mini.backend.llvm.IrExt
import mini.backend.llvm.IrLinkage
import mini.backend.llvm.IrOp
import mini.backend.llvm.IrSection
import mini.backend.llvm.IrStrings
import mini.backend.llvm.IrType
import mini.backend.llvm.LlvmTarget
import mini.llvm.*

/** An LLVM value (instruction, constant, global, parameter): an object around the handle, so it fits into lists and maps. */
class ApiVal(val ref: COpaquePointer)

/** A basic block. A block that was created but never appended to the function is deleted at the end of the function. */
class ApiBlock(val ref: COpaquePointer) {
    var appended: Boolean = false
}

/**
 * [IrBuilder] on the LLVM C API (compiler/llvm-c, docs/plan-kotlin.md section 10g phase 2): the module is built in memory, no text.
 *
 * The caller owns the context and the module (the JIT hands over a thread-safe context, the object-file path a plain one); this class
 * owns its instruction builders only ([dispose]). The module's target triple and data layout must be set before the lowering runs
 * (the builder takes the ABI alignments of loads and stores from the data layout).
 *
 * Differences to the text builder that do not change the program: unnamed temporaries, instructions with constant operands are
 * folded by LLVM's IRBuilder, `alloca`s are inserted before an `freeze` placeholder in the entry block that is erased at the end of
 * the function (so they stay in creation order whatever the current block is).
 */
class ApiIrBuilder(val context: LLVMContextRef, val module: LLVMModuleRef) : IrBuilder<ApiVal, ApiBlock> {
    private val builder: LLVMBuilderRef = LLVMCreateBuilderInContext(context)
    private val allocaBuilder: LLVMBuilderRef = LLVMCreateBuilderInContext(context)
    private val emptyName: CPointer<ByteVar> = nativeHeap.allocArray<ByteVar>(1)

    private val tI1: LLVMTypeRef = LLVMInt1TypeInContext(context)
    private val tI8: LLVMTypeRef = LLVMInt8TypeInContext(context)
    private val tI16: LLVMTypeRef = LLVMInt16TypeInContext(context)
    private val tI32: LLVMTypeRef = LLVMInt32TypeInContext(context)
    private val tI64: LLVMTypeRef = LLVMInt64TypeInContext(context)
    private val tFloat: LLVMTypeRef = LLVMFloatTypeInContext(context)
    private val tDouble: LLVMTypeRef = LLVMDoubleTypeInContext(context)
    private val tPtr: LLVMTypeRef = LLVMPointerTypeInContext(context, 0)
    private val tVoid: LLVMTypeRef = LLVMVoidTypeInContext(context)

    private val kNounwind: Int = enumKind("nounwind")
    private val kSanitizeAddress: Int = enumKind("sanitize_address")
    private val kAlwaysInline: Int = enumKind("alwaysinline")
    private val kNoReturn: Int = enumKind("noreturn")
    private val kMemory: Int = enumKind("memory")
    private val kZeroExt: Int = enumKind("zeroext")
    private val kSignExt: Int = enumKind("signext")
    private val kProf: Int = mdKind("prof")
    private val kTbaa: Int = mdKind("tbaa")
    private var weights: COpaquePointer? = null

    override val none: ApiVal = ApiVal(LLVMConstPointerNull(tPtr))

    // the function being defined
    private var fn: COpaquePointer? = null
    private var entry: ApiBlock? = null
    private var placeholder: COpaquePointer? = null
    private val created = ArrayList<ApiBlock>()

    fun dispose() {
        LLVMDisposeBuilder(builder)
        LLVMDisposeBuilder(allocaBuilder)
        nativeHeap.free(emptyName)
    }

    /** The module as text (for `--emit-ll` through the API path and for diagnostics). */
    fun printModule(): String {
        val s = LLVMPrintModuleToString(module)
        val text = s.toKString()
        LLVMDisposeMessage(s)
        return text
    }

    private fun enumKind(name: String): Int = memScoped { LLVMGetEnumAttributeKindForName(name.cstr.ptr, name.length.toLong()) }

    private fun mdKind(name: String): Int = memScoped { LLVMGetMDKindIDInContext(context, name.cstr.ptr, name.length) }

    private fun ty(t: IrType): LLVMTypeRef = when (t) {
        IrType.I1 -> tI1
        IrType.I8 -> tI8
        IrType.I16 -> tI16
        IrType.I32 -> tI32
        IrType.I64 -> tI64
        IrType.FLOAT -> tFloat
        IrType.DOUBLE -> tDouble
        IrType.PTR -> tPtr
        IrType.VOID -> tVoid
    }

    private fun nameOrEmpty(name: String): Boolean = name.isNotEmpty()

    // ---- module ---------------------------------------------------------------------------------

    override fun beginModule(sourceName: String, links: List<String>, target: LlvmTarget) {
        memScoped {
            LLVMSetSourceFileName(module, sourceName.cstr.ptr, IrStrings.utf8(sourceName).size.toLong())
            val triple = target.triple
            if (triple != null) LLVMSetTarget(module, triple.cstr.ptr)
            val dl = target.dataLayout
            if (dl != null) LLVMSetDataLayout(module, dl.cstr.ptr)
        }
        if (!target.pendingAccessor) {
            memScoped {
                val g = LLVMAddGlobal(module, tPtr, "mk_pending_exception".cstr.ptr)
                LLVMSetThreadLocal(g, 1)
                LLVMSetThreadLocalMode(g, 3) // LLVMInitialExecTLSModel: no __tls_get_addr call per access
            }
        }
    }

    override fun defineNamedStruct(name: String, fields: List<IrType>) {
        memScoped {
            val st = LLVMStructCreateNamed(context, name.cstr.ptr)
            val arr = allocArray<COpaquePointerVar>(fields.size)
            for (i in fields.indices) arr[i] = ty(fields[i])
            LLVMStructSetBody(st, arr, fields.size, 1)
        }
    }

    override fun comment(s: IrSection, text: String) {}

    override fun blankLine(s: IrSection) {}

    // ---- constants --------------------------------------------------------------------------------

    override fun constInt(t: IrType, v: Long): ApiVal = ApiVal(LLVMConstInt(ty(t), v, 1))

    override fun constFloat(t: IrType, v: Double): ApiVal = ApiVal(LLVMConstReal(ty(t), v))

    override fun constNull(): ApiVal = ApiVal(LLVMConstPointerNull(tPtr))

    override fun constArray(elem: IrType, items: List<ApiVal>): ApiVal = memScoped {
        val arr = allocArray<COpaquePointerVar>(items.size)
        for (i in items.indices) arr[i] = items[i].ref
        ApiVal(LLVMConstArray2(ty(elem), arr, items.size.toLong()))
    }

    override fun constPackedStruct(items: List<ApiVal>): ApiVal = memScoped {
        val arr = allocArray<COpaquePointerVar>(items.size)
        for (i in items.indices) arr[i] = items[i].ref
        ApiVal(LLVMConstStructInContext(context, arr, items.size, 1))
    }

    private fun namedType(name: String): LLVMTypeRef {
        val t = memScoped { LLVMGetTypeByName2(context, name.cstr.ptr) }
        if (t == null) throw EmitError("unknown struct type $name")
        return t
    }

    override fun constNamedStruct(typeName: String, items: List<ApiVal>): ApiVal = memScoped {
        val arr = allocArray<COpaquePointerVar>(items.size)
        for (i in items.indices) arr[i] = items[i].ref
        ApiVal(LLVMConstNamedStruct(namedType(typeName), arr, items.size))
    }

    override fun constCString(text: String): ApiVal = memScoped {
        val bytes = IrStrings.utf8(text)
        val buf = allocArray<ByteVar>(bytes.size + 1)
        for (i in bytes.indices) buf[i] = bytes[i].toByte()
        ApiVal(LLVMConstStringInContext(context, buf, bytes.size, 0))
    }

    // ---- globals ------------------------------------------------------------------------------------

    private fun findGlobal(name: String): COpaquePointer? {
        val f = memScoped { LLVMGetNamedFunction(module, name.cstr.ptr) }
        if (f != null) return f
        return memScoped { LLVMGetNamedGlobal(module, name.cstr.ptr) }
    }

    override fun global(name: String): ApiVal {
        val g = findGlobal(name)
        if (g == null) throw EmitError("undefined global $name")
        return ApiVal(g)
    }

    override fun externTypeInfo(sym: String): ApiVal {
        val g = findGlobal(sym)
        if (g != null) return ApiVal(g)
        return memScoped { ApiVal(LLVMAddGlobal(module, namedType("MkTypeInfo"), sym.cstr.ptr)) }
    }

    private fun linkageOf(l: IrLinkage): Int = if (l == IrLinkage.INTERNAL) LLVMInternalLinkage else if (l == IrLinkage.PRIVATE) LLVMPrivateLinkage else LLVMExternalLinkage

    override fun declareGlobalOfStruct(name: String, typeName: String, linkage: IrLinkage) {
        if (findGlobal(name) != null) return
        val g = memScoped { LLVMAddGlobal(module, namedType(typeName), name.cstr.ptr) }
        LLVMSetLinkage(g, linkageOf(linkage))
    }

    override fun defineGlobal(s: IrSection, name: String, init: ApiVal, constant: Boolean, linkage: IrLinkage, align: Int, unnamedAddr: Boolean): ApiVal {
        val existing = findGlobal(name)
        val g: COpaquePointer = if (existing != null) existing else memScoped { LLVMAddGlobal(module, LLVMTypeOf(init.ref), name.cstr.ptr) }
        LLVMSetInitializer(g, init.ref)
        LLVMSetGlobalConstant(g, if (constant) 1 else 0)
        LLVMSetLinkage(g, linkageOf(linkage))
        if (unnamedAddr) LLVMSetUnnamedAddress(g, LLVMGlobalUnnamedAddr)
        if (align > 0) LLVMSetAlignment(g, align)
        return ApiVal(g)
    }

    // ---- functions -------------------------------------------------------------------------------------

    private fun attr(kind: Int, value: Long): LLVMAttributeRef = LLVMCreateEnumAttribute(context, kind, value)

    private fun applyFnAttrs(f: COpaquePointer, attrs: IrAttrs) {
        if (attrs == IrAttrs.NONE) return
        val idx = LLVMAttributeFunctionIndex
        LLVMAddAttributeAtIndex(f, idx, attr(kNounwind, 0L))
        if (attrs == IrAttrs.STD || attrs == IrAttrs.INLINE) LLVMAddAttributeAtIndex(f, idx, attr(kSanitizeAddress, 0L))
        if (attrs == IrAttrs.INLINE) LLVMAddAttributeAtIndex(f, idx, attr(kAlwaysInline, 0L))
        if (attrs == IrAttrs.PURE_INLINE) {
            LLVMAddAttributeAtIndex(f, idx, attr(kAlwaysInline, 0L))
            LLVMAddAttributeAtIndex(f, idx, attr(kMemory, 0L))
        }
        if (attrs == IrAttrs.NORETURN) LLVMAddAttributeAtIndex(f, idx, attr(kNoReturn, 0L))
        if (attrs == IrAttrs.PURE) LLVMAddAttributeAtIndex(f, idx, attr(kMemory, 0L))
        // LLVM 18 ModRef.h: ArgMem/ InaccessibleMem/ Other each occupy two bits;
        // Ref in all three locations is 1 + (1 << 2) + (1 << 4) == 21 (memory(read)).
        if (attrs == IrAttrs.READONLY) LLVMAddAttributeAtIndex(f, idx, attr(kMemory, 21L))
    }

    private fun extKind(e: IrExt): Int = if (e == IrExt.ZERO) kZeroExt else kSignExt

    private fun fnType(ret: IrType, params: List<IrType>, vararg: Boolean): LLVMTypeRef = memScoped {
        val arr = allocArray<COpaquePointerVar>(params.size)
        for (i in params.indices) arr[i] = ty(params[i])
        LLVMFunctionType(ty(ret), arr, params.size, if (vararg) 1 else 0)
    }

    /** Gets or adds the function [name]; a new one gets its type, attributes and linkage. */
    private fun getOrAddFunction(
        name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, linkage: IrLinkage, vararg: Boolean,
    ): COpaquePointer {
        val known = memScoped { LLVMGetNamedFunction(module, name.cstr.ptr) }
        if (known != null) return known
        val f = memScoped { LLVMAddFunction(module, name.cstr.ptr, fnType(ret, params, vararg)) }
        applyFnAttrs(f, attrs)
        if (retExt != IrExt.NONE) LLVMAddAttributeAtIndex(f, LLVMAttributeReturnIndex, attr(extKind(retExt), 0L))
        for (i in params.indices) {
            if (exts[i] != IrExt.NONE) LLVMAddAttributeAtIndex(f, LLVMAttributeFirstArgIndex + i, attr(extKind(exts[i]), 0L))
        }
        if (linkage != IrLinkage.EXTERNAL) LLVMSetLinkage(f, linkageOf(linkage))
        return f
    }

    override fun declareExternal(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, vararg: Boolean): ApiVal =
        ApiVal(getOrAddFunction(name, ret, retExt, params, exts, attrs, IrLinkage.EXTERNAL, vararg))

    override fun hasFunction(name: String): Boolean = memScoped { LLVMGetNamedFunction(module, name.cstr.ptr) } != null

    override fun declareDefined(name: String, ret: IrType, retExt: IrExt, params: List<IrType>, exts: List<IrExt>, attrs: IrAttrs, linkage: IrLinkage): ApiVal =
        ApiVal(getOrAddFunction(name, ret, retExt, params, exts, attrs, linkage, false))

    override fun beginFunction(
        s: IrSection, name: String, ret: IrType, retExt: IrExt, paramNames: List<String>, params: List<IrType>, exts: List<IrExt>,
        attrs: IrAttrs, linkage: IrLinkage,
    ) {
        val f = getOrAddFunction(name, ret, retExt, params, exts, attrs, linkage, false)
        fn = f
        created.clear()
        val e = memScoped { ApiBlock(LLVMAppendBasicBlockInContext(context, f, "entry".cstr.ptr)) }
        e.appended = true
        entry = e
        LLVMPositionBuilderAtEnd(builder, e.ref)
        val ph = LLVMBuildFreeze(builder, LLVMGetUndef(tI32), emptyName)
        placeholder = ph
        LLVMPositionBuilderBefore(allocaBuilder, ph)
    }

    override fun exportCurrentFunction(name: String) {
        throw IllegalStateException("wasm exports need the text backend (--emit-ll)")
    }

    override fun param(i: Int): ApiVal = ApiVal(LLVMGetParam(fn!!, i))

    override fun entryBlock(): ApiBlock = entry!!

    override fun endFunction() {
        val ph = placeholder
        if (ph != null) LLVMInstructionEraseFromParent(ph)
        placeholder = null
        val f = fn!!
        // blocks the lowering created but never used (an `else` that does not exist): put them in and delete them
        for (b in created) {
            if (!b.appended) {
                LLVMAppendExistingBasicBlock(f, b.ref)
                LLVMDeleteBasicBlock(b.ref)
            }
        }
        created.clear()
        fn = null
    }

    // ---- blocks and instructions --------------------------------------------------------------------------

    override fun newBlock(name: String): ApiBlock {
        val b = memScoped { ApiBlock(LLVMCreateBasicBlockInContext(context, name.cstr.ptr)) }
        created.add(b)
        return b
    }

    override fun appendBlock(b: ApiBlock) {
        LLVMAppendExistingBasicBlock(fn!!, b.ref)
        b.appended = true
        LLVMPositionBuilderAtEnd(builder, b.ref)
    }

    override fun alloca(t: IrType, name: String): ApiVal {
        if (!nameOrEmpty(name)) return ApiVal(LLVMBuildAlloca(allocaBuilder, ty(t), emptyName))
        return memScoped { ApiVal(LLVMBuildAlloca(allocaBuilder, ty(t), name.cstr.ptr)) }
    }

    override fun load(t: IrType, ptr: ApiVal, align: Int, name: String): ApiVal {
        val v = if (nameOrEmpty(name)) memScoped { LLVMBuildLoad2(builder, ty(t), ptr.ref, name.cstr.ptr) } else LLVMBuildLoad2(builder, ty(t), ptr.ref, emptyName)
        if (align > 0) LLVMSetAlignment(v, align)
        return ApiVal(v)
    }

    override fun store(v: ApiVal, ptr: ApiVal, align: Int) {
        val s = LLVMBuildStore(builder, v.ref, ptr.ref)
        if (align > 0) LLVMSetAlignment(s, align)
    }

    /**
     * The access tag [tag] (see [IrBuilder.loadTagged]): `!{!T, !T, i64 0}` over the scalar type node `!T = !{!"tag", !root, i64 0}`.
     * LLVM uniques metadata nodes, so building it again for every access yields the same node (and no pointer is kept in a collection).
     */
    private fun tbaaTag(tag: String): COpaquePointer = memScoped {
        val rv = allocArray<COpaquePointerVar>(1)
        rv[0] = LLVMMDStringInContext(context, "mini tbaa".cstr.ptr, 9)
        val root = LLVMMDNodeInContext(context, rv, 1)
        val tv = allocArray<COpaquePointerVar>(3)
        tv[0] = LLVMMDStringInContext(context, tag.cstr.ptr, IrStrings.utf8(tag).size)
        tv[1] = root
        tv[2] = LLVMConstInt(tI64, 0L, 0)
        val type = LLVMMDNodeInContext(context, tv, 3)
        val av = allocArray<COpaquePointerVar>(3)
        av[0] = type
        av[1] = type
        av[2] = LLVMConstInt(tI64, 0L, 0)
        LLVMMDNodeInContext(context, av, 3)
    }

    override fun loadTagged(t: IrType, ptr: ApiVal, tag: String, name: String): ApiVal {
        val v = load(t, ptr, 0, name)
        LLVMSetMetadata(v.ref, kTbaa, tbaaTag(tag))
        return v
    }

    override fun storeTagged(v: ApiVal, ptr: ApiVal, tag: String) {
        val s = LLVMBuildStore(builder, v.ref, ptr.ref)
        LLVMSetMetadata(s, kTbaa, tbaaTag(tag))
    }

    override fun gep(base: ApiVal, offset: ApiVal, inbounds: Boolean, name: String): ApiVal = memScoped {
        val idx = allocArray<COpaquePointerVar>(1)
        idx[0] = offset.ref
        val nm = if (nameOrEmpty(name)) name.cstr.ptr else emptyName
        if (inbounds) ApiVal(LLVMBuildInBoundsGEP2(builder, tI8, base.ref, idx, 1, nm)) else ApiVal(LLVMBuildGEP2(builder, tI8, base.ref, idx, 1, nm))
    }

    private fun binRaw(op: IrOp, a: COpaquePointer, b: COpaquePointer, nm: CPointer<ByteVar>): COpaquePointer = when (op) {
        IrOp.ADD -> LLVMBuildAdd(builder, a, b, nm)
        IrOp.SUB -> LLVMBuildSub(builder, a, b, nm)
        IrOp.MUL -> LLVMBuildMul(builder, a, b, nm)
        IrOp.SDIV -> LLVMBuildSDiv(builder, a, b, nm)
        IrOp.SREM -> LLVMBuildSRem(builder, a, b, nm)
        IrOp.AND -> LLVMBuildAnd(builder, a, b, nm)
        IrOp.OR -> LLVMBuildOr(builder, a, b, nm)
        IrOp.XOR -> LLVMBuildXor(builder, a, b, nm)
        IrOp.SHL -> LLVMBuildShl(builder, a, b, nm)
        IrOp.ASHR -> LLVMBuildAShr(builder, a, b, nm)
        IrOp.LSHR -> LLVMBuildLShr(builder, a, b, nm)
        IrOp.FADD -> LLVMBuildFAdd(builder, a, b, nm)
        IrOp.FSUB -> LLVMBuildFSub(builder, a, b, nm)
        IrOp.FMUL -> LLVMBuildFMul(builder, a, b, nm)
        IrOp.FDIV -> LLVMBuildFDiv(builder, a, b, nm)
        IrOp.FREM -> LLVMBuildFRem(builder, a, b, nm)
    }

    override fun bin(op: IrOp, a: ApiVal, b: ApiVal, name: String): ApiVal {
        if (!nameOrEmpty(name)) return ApiVal(binRaw(op, a.ref, b.ref, emptyName))
        return memScoped { ApiVal(binRaw(op, a.ref, b.ref, name.cstr.ptr)) }
    }

    private fun cmpRaw(pred: IrCmp, a: COpaquePointer, b: COpaquePointer, nm: CPointer<ByteVar>): COpaquePointer = when (pred) {
        IrCmp.IEQ -> LLVMBuildICmp(builder, LLVMIntEQ, a, b, nm)
        IrCmp.INE -> LLVMBuildICmp(builder, LLVMIntNE, a, b, nm)
        IrCmp.SLT -> LLVMBuildICmp(builder, LLVMIntSLT, a, b, nm)
        IrCmp.SLE -> LLVMBuildICmp(builder, LLVMIntSLE, a, b, nm)
        IrCmp.SGT -> LLVMBuildICmp(builder, LLVMIntSGT, a, b, nm)
        IrCmp.SGE -> LLVMBuildICmp(builder, LLVMIntSGE, a, b, nm)
        IrCmp.ULT -> LLVMBuildICmp(builder, LLVMIntULT, a, b, nm)
        IrCmp.ULE -> LLVMBuildICmp(builder, LLVMIntULE, a, b, nm)
        IrCmp.UGT -> LLVMBuildICmp(builder, LLVMIntUGT, a, b, nm)
        IrCmp.UGE -> LLVMBuildICmp(builder, LLVMIntUGE, a, b, nm)
        IrCmp.FOEQ -> LLVMBuildFCmp(builder, LLVMRealOEQ, a, b, nm)
        IrCmp.FUNE -> LLVMBuildFCmp(builder, LLVMRealUNE, a, b, nm)
        IrCmp.FOLT -> LLVMBuildFCmp(builder, LLVMRealOLT, a, b, nm)
        IrCmp.FOLE -> LLVMBuildFCmp(builder, LLVMRealOLE, a, b, nm)
        IrCmp.FOGT -> LLVMBuildFCmp(builder, LLVMRealOGT, a, b, nm)
        IrCmp.FOGE -> LLVMBuildFCmp(builder, LLVMRealOGE, a, b, nm)
    }

    override fun cmp(pred: IrCmp, a: ApiVal, b: ApiVal, name: String): ApiVal {
        if (!nameOrEmpty(name)) return ApiVal(cmpRaw(pred, a.ref, b.ref, emptyName))
        return memScoped { ApiVal(cmpRaw(pred, a.ref, b.ref, name.cstr.ptr)) }
    }

    override fun fneg(a: ApiVal): ApiVal = ApiVal(LLVMBuildFNeg(builder, a.ref, emptyName))

    private fun castRaw(op: IrCast, a: COpaquePointer, to: LLVMTypeRef, nm: CPointer<ByteVar>): COpaquePointer = when (op) {
        IrCast.ZEXT -> LLVMBuildZExt(builder, a, to, nm)
        IrCast.SEXT -> LLVMBuildSExt(builder, a, to, nm)
        IrCast.TRUNC -> LLVMBuildTrunc(builder, a, to, nm)
        IrCast.SITOFP -> LLVMBuildSIToFP(builder, a, to, nm)
        IrCast.UITOFP -> LLVMBuildUIToFP(builder, a, to, nm)
        IrCast.FPEXT -> LLVMBuildFPExt(builder, a, to, nm)
        IrCast.FPTRUNC -> LLVMBuildFPTrunc(builder, a, to, nm)
        IrCast.INTTOPTR -> LLVMBuildIntToPtr(builder, a, to, nm)
        IrCast.PTRTOINT -> LLVMBuildPtrToInt(builder, a, to, nm)
    }

    override fun cast(op: IrCast, a: ApiVal, to: IrType, name: String): ApiVal {
        if (!nameOrEmpty(name)) return ApiVal(castRaw(op, a.ref, ty(to), emptyName))
        return memScoped { ApiVal(castRaw(op, a.ref, ty(to), name.cstr.ptr)) }
    }

    override fun select(c: ApiVal, a: ApiVal, b: ApiVal): ApiVal = ApiVal(LLVMBuildSelect(builder, c.ref, a.ref, b.ref, emptyName))

    override fun phi(t: IrType, v1: ApiVal, b1: ApiBlock, v2: ApiVal, b2: ApiBlock): ApiVal = memScoped {
        val p = LLVMBuildPhi(builder, ty(t), emptyName)
        val vals = allocArray<COpaquePointerVar>(2)
        val blocks = allocArray<COpaquePointerVar>(2)
        vals[0] = v1.ref
        vals[1] = v2.ref
        blocks[0] = b1.ref
        blocks[1] = b2.ref
        LLVMAddIncoming(p, vals, blocks, 2)
        ApiVal(p)
    }

    override fun fptosiSat(a: ApiVal, bits: Int): ApiVal = memScoped {
        val id = LLVMLookupIntrinsicID("llvm.fptosi.sat".cstr.ptr, 15L)
        val tys = allocArray<COpaquePointerVar>(2)
        tys[0] = if (bits == 32) tI32 else tI64
        tys[1] = tDouble
        val decl = LLVMGetIntrinsicDeclaration(module, id, tys, 2L)
        val args = allocArray<COpaquePointerVar>(1)
        args[0] = a.ref
        ApiVal(LLVMBuildCall2(builder, LLVMGlobalGetValueType(decl), decl, args, 1, emptyName))
    }

    override fun call(callee: ApiVal, ret: IrType, retExt: IrExt, args: List<ApiVal>, exts: List<IrExt>, fixed: Int, name: String): ApiVal = memScoped {
        val nFixed = if (fixed >= 0) fixed else args.size
        val ptys = allocArray<COpaquePointerVar>(nFixed)
        for (i in 0 until nFixed) ptys[i] = LLVMTypeOf(args[i].ref)
        val fty = LLVMFunctionType(ty(ret), ptys, nFixed, if (fixed >= 0) 1 else 0)
        val argv = allocArray<COpaquePointerVar>(args.size)
        for (i in args.indices) argv[i] = args[i].ref
        val isVoid = ret == IrType.VOID
        val nm = if (nameOrEmpty(name) && !isVoid) name.cstr.ptr else emptyName
        val c = LLVMBuildCall2(builder, fty, callee.ref, argv, args.size, nm)
        if (retExt != IrExt.NONE) LLVMAddCallSiteAttribute(c, LLVMAttributeReturnIndex, attr(extKind(retExt), 0L))
        for (i in args.indices) {
            if (exts[i] != IrExt.NONE) LLVMAddCallSiteAttribute(c, LLVMAttributeFirstArgIndex + i, attr(extKind(exts[i]), 0L))
        }
        if (isVoid) none else ApiVal(c)
    }

    override fun br(target: ApiBlock) {
        LLVMBuildBr(builder, target.ref)
    }

    private fun branchWeights(): COpaquePointer {
        val known = weights
        if (known != null) return known
        val w = memScoped {
            val vals = allocArray<COpaquePointerVar>(3)
            vals[0] = LLVMMDStringInContext(context, "branch_weights".cstr.ptr, 14)
            vals[1] = LLVMConstInt(tI32, 1L, 0)
            vals[2] = LLVMConstInt(tI32, 2000L, 0)
            LLVMMDNodeInContext(context, vals, 3)
        }
        weights = w
        return w
    }

    override fun condBr(c: ApiVal, t: ApiBlock, f: ApiBlock, cold: Boolean) {
        val br = LLVMBuildCondBr(builder, c.ref, t.ref, f.ref)
        if (cold) LLVMSetMetadata(br, kProf, branchWeights())
    }

    override fun ret(v: ApiVal) {
        LLVMBuildRet(builder, v.ref)
    }

    override fun retVoid() {
        LLVMBuildRetVoid(builder)
    }

    override fun unreachable() {
        LLVMBuildUnreachable(builder)
    }

    override fun finish(): String = ""
}
