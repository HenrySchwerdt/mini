package mini.backend.llvm

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.EmitError
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirWasmExport
import mini.backend.WasmAbi
import mini.backend.WasmKind
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.NativeConventions
import mini.backend.Tracking
import mini.backend.RcFunctionPlan
import mini.backend.RcOpt
import mini.backend.RcPlan
import mini.backend.TBoolean
import mini.backend.TypeCheckPlan
import mini.backend.TypeCheckUse
import mini.backend.TypeChecks
import mini.backend.TBoxed
import mini.backend.MirExtern
import mini.backend.TByte
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TFloat
import mini.backend.TPtr
import mini.backend.TShort
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TNull
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.ThrowAnalysis
import mini.backend.UnaryOp
import mini.backend.isOwned
import mini.backend.isRef

/**
 * MIR to LLVM IR through an [IrBuilder]: the alternative to [mini.backend.CEmitter] (docs/plan-kotlin.md section 10g).
 * [LlvmEmitter] runs it with the text builder (`.ll` files), the in-process LLVM C API builder (compiler/llvm-c) with the API one.
 *
 * The lowering follows CEmitter statement by statement and uses the same RcOpt decisions ([RcPlan]), so both
 * backends make the same reference-counting and exception-path choices:
 *
 *  - every MIR local is an `alloca` in the entry block (LLVM's mem2reg/SROA turns them into SSA values);
 *  - reference locals are NULL-initialised at the top of their scope and released at every exit of it;
 *  - the exception ABI is the error-return one of the C backend: after a call that can throw, the thread-local
 *    `mk_pending_exception` is tested and the exit path releases the locals it leaves (cold `mk_release`);
 *  - objects have the C layout, computed here ([Layouts]); fields are byte-offset GEPs, so the IR does not
 *    depend on a data layout; type infos, vtables and itables are constant globals with the same contents;
 *  - the runtime is libminirt: the IR calls the functions of minirt.h by name (declared from the MIR types);
 *  - `inlineRc` (RcOpt on, no MK_STATS) uses alwaysinline helpers with the fast paths of MKH_RETAIN/MKH_REL.
 *
 * Pure Kotlin like the rest of the backend (self-hosting rule, section 10a).
 */
object LlvmLowering {
    /**
     * [rcOpt], [strict] and [conventions] mean what they mean for [mini.backend.CEmitter.emit] (RcOpt, Strict code generation
     * with tracked values, borrowed results / owned parameters of the stdlib hot paths); [stats] is the MK_STATS build.
     */
    fun <V : Any, B : Any> lower(
        module: MirModule,
        builder: IrBuilder<V, B>,
        rcOpt: Boolean = true,
        strict: Boolean = true,
        conventions: Boolean = true,
        stats: Boolean = false,
        target: LlvmTarget = LlvmTarget.HOST,
        externalClasses: Boolean = false,
        externalFunctions: Boolean = false,
        entrySymbol: String = "mk_main",
        userMainSymbol: String = "mkh_user_main",
        externalFunctionSignatures: List<MirFunction> = emptyList(),
        externalFunctionOwnedMasks: Map<String, BooleanArray> = emptyMap(),
        externalBorrowedFunctions: Set<String> = emptySet(),
        externalClassNames: Set<String> = emptySet(),
        exportedClassNames: Set<String> = emptySet(),
    ) {
        val tr = Tracking(module, strict)
        val plan = if (rcOpt) RcOpt.analyze(module, tr, conventions, externalFunctionSignatures, externalFunctionOwnedMasks, externalBorrowedFunctions) else null
        Lowering(module, plan, tr, stats, target, builder, externalClasses, externalFunctions, entrySymbol, userMainSymbol, externalFunctionSignatures, externalClassNames, exportedClassNames).run()
    }
}

private const val WASM_INIT_FACADES = "mini_wasm_init_facades"

private class Scope(val locals: List<MirLocal>, val loop: MirLabel?)
private class Handler<B : Any>(val label: B, val scopeIndex: Int)

private class Lowering<V : Any, B : Any>(
    val module: MirModule,
    val rcPlan: RcPlan?,
    val tr: Tracking,
    val stats: Boolean,
    val target: LlvmTarget,
    val b: IrBuilder<V, B>,
    val externalClasses: Boolean,
    val externalFunctions: Boolean,
    val entrySymbol: String,
    val userMainSymbol: String,
    val externalFunctionSignatures: List<MirFunction>,
    val externalClassNames: Set<String>,
    val exportedClassNames: Set<String>,
) {
    var curFn: MirFunction? = null
    val newaUsed = LinkedHashSet<String>()

    fun rcT(t: MirType): Boolean = tr.rc(curFn, t)
    fun rcL(l: MirLocal): Boolean = tr.rcLocal(curFn, l)
    fun ownedE(e: MirExpr): Boolean = tr.owned(curFn, e)

    val sig = HashMap<String, MirFunction>()
    val classes = HashMap<String, MirClass>()
    lateinit var layouts: Layouts
    lateinit var throwing: ThrowAnalysis
    lateinit var typeCheckPlan: TypeCheckPlan
    var customIsInstanceSymbol = false

    val inlineRc: Boolean = rcPlan != null && !stats
    val szT: IrType = if (target.ptrBytes == 8) IrType.I64 else IrType.I32
    val pb: Int = target.ptrBytes

    fun isExternalClass(c: MirClass): Boolean = c.external || c.cName in externalClassNames

    // module level
    val literals = HashMap<String, V>() // kotlin value -> global
    val cstrings = HashMap<String, V>() // text -> global

    // per function
    var terminated = false
    lateinit var curBlock: B
    var lblCounter = 0
    val scopes = ArrayList<Scope>()
    var currentReturn: MirType = TUnit
    var fplan: RcFunctionPlan? = null
    val dead = HashSet<Int>()
    val pending = HashSet<Int>()
    var curStmt: MirStmt? = null
    val handlers = ArrayList<Handler<B>>()
    val caught = ArrayList<V>()
    var tryCounter = 0
    val usedLabels = HashSet<String>()
    val slots = HashMap<Int, V>()
    val namedBlocks = HashMap<String, B>()

    // ---- module ---------------------------------------------------------------------

    fun run() {
        for (f in module.functions) {
            if (sig.put(f.cName, f) != null) throw EmitError("duplicate C symbol ${f.cName}")
            if (f.cName == "mk_is_instance") customIsInstanceSymbol = true
        }
        for (f in externalFunctionSignatures) {
            if (f.cName == "mk_is_instance") customIsInstanceSymbol = true
            if (!sig.containsKey(f.cName)) sig[f.cName] = f
        }
        for (c in module.classes) classes[c.cName] = c
        layouts = Layouts(classes, target)
        throwing = ThrowAnalysis(module, externalFunctionSignatures).run()
        typeCheckPlan = if (customIsInstanceSymbol) TypeCheckPlan.EMPTY else TypeChecks.analyze(module, externalClassNames)

        b.beginModule(module.sourceName, module.links, target)
        b.defineNamedStruct("MkTypeInfo", listOf(IrType.PTR, szT, IrType.PTR, IrType.PTR, IrType.PTR, szT, IrType.PTR, szT))
        predeclare()
        for (f in module.functions) emitFunction(f)
        val userMain = module.functions.firstOrNull { it.cName == Mangling.MAIN }
        if (target.wasmLib) {
            if (userMain != null) throw EmitError("a wasm library has no entry point: remove `main`")
            emitWasmLib()
        } else if (userMain != null) emitMainWrapper(userMain)
        emitClasses()
        emitTypeCheckHelpers()
        emitPrelude()
    }

    /** Everything other code refers to before it is defined (the API builder needs the declaration first; the text needs nothing). */
    fun predeclare() {
        for (f in module.functions) {
            val name = fname(f.cName)
            b.declareDefined(name, ity(f.returnType), rext(f.returnType), f.params.map { ity(it.type) }, f.params.map { ext(it.type) }, IrAttrs.STD, IrLinkage.EXTERNAL)
            val cls = f.constructorOf
            if (cls != null) {
                val ps = f.params.drop(1)
                b.declareDefined("${f.cName}_new", IrType.PTR, IrExt.NONE, ps.map { ity(it.type) }, ps.map { ext(it.type) }, IrAttrs.STD, IrLinkage.EXTERNAL)
            }
        }
        for (f in externalFunctionSignatures) {
            if (module.functions.any { it.cName == f.cName }) continue
            val name = fname(f.cName)
            if (externalFunctions) b.declareExternal(name, ity(f.returnType), rext(f.returnType), f.params.map { ity(it.type) }, f.params.map { ext(it.type) }, IrAttrs.STD, false)
            else b.declareDefined(name, ity(f.returnType), rext(f.returnType), f.params.map { ity(it.type) }, f.params.map { ext(it.type) }, IrAttrs.STD, IrLinkage.EXTERNAL)
            if (f.constructorOf != null) {
                val ps = f.params.drop(1)
                if (externalFunctions) b.declareExternal("${f.cName}_new", IrType.PTR, IrExt.NONE, ps.map { ity(it.type) }, ps.map { ext(it.type) }, IrAttrs.STD, false)
                else b.declareDefined("${f.cName}_new", IrType.PTR, IrExt.NONE, ps.map { ity(it.type) }, ps.map { ext(it.type) }, IrAttrs.STD, IrLinkage.EXTERNAL)
            }
        }
        for (c in module.classes) {
            if (isExternalClass(c)) {
                b.externTypeInfo("${c.cName}_ti")
                if (!c.isInterface && c.objectCtor != null) {
                    if (externalFunctions || c.cName in externalClassNames) b.declareExternal("${c.cName}_get", IrType.PTR, IrExt.NONE, emptyList(), emptyList(), IrAttrs.STD, false)
                    else b.declareDefined("${c.cName}_get", IrType.PTR, IrExt.NONE, emptyList(), emptyList(), IrAttrs.STD, IrLinkage.EXTERNAL)
                }
                continue
            }
            b.declareGlobalOfStruct("${c.cName}_ti", "MkTypeInfo", IrLinkage.INTERNAL)
            if (!c.isInterface && c.objectCtor != null) {
                b.declareDefined("${c.cName}_get", IrType.PTR, IrExt.NONE, emptyList(), emptyList(), IrAttrs.STD, IrLinkage.INTERNAL)
            }
        }
        // Stub-only MIR may mention an imported object getter without carrying its MirClass record (ObjectGet itself
        // is sufficient for lowering). In separate-library mode the ABI supplies those names independently.
        if (externalFunctions) for (cName in externalClassNames) {
            b.externTypeInfo("${cName}_ti")
            b.declareExternal("${cName}_get", IrType.PTR, IrExt.NONE, emptyList(), emptyList(), IrAttrs.STD, false)
        }
        b.declareDefined("mkh_abstract", IrType.VOID, IrExt.NONE, emptyList(), emptyList(), IrAttrs.STD, IrLinkage.INTERNAL)
        val p1 = listOf(IrType.PTR)
        val p2 = listOf(IrType.PTR, IrType.PTR)
        val e1 = listOf(IrExt.NONE)
        val e2 = listOf(IrExt.NONE, IrExt.NONE)
        b.declareDefined("mkh_cast_nn", IrType.PTR, IrExt.NONE, p2, e2, IrAttrs.STD, IrLinkage.INTERNAL)
        b.declareDefined("mkh_nn", IrType.PTR, IrExt.NONE, p1, e1, IrAttrs.STD, IrLinkage.INTERNAL)
        for (t in listOf(IrType.I32, IrType.I64)) {
            val n = if (t == IrType.I32) "int" else "long"
            val pt = listOf(t, t)
            b.declareDefined("mkh_div_$n", t, IrExt.NONE, pt, e2, IrAttrs.STD, IrLinkage.INTERNAL)
            b.declareDefined("mkh_rem_$n", t, IrExt.NONE, pt, e2, IrAttrs.STD, IrLinkage.INTERNAL)
        }
        if (inlineRc) {
            b.declareDefined("mkh_retain", IrType.VOID, IrExt.NONE, p1, e1, IrAttrs.INLINE, IrLinkage.INTERNAL)
            b.declareDefined("mkh_rel", IrType.VOID, IrExt.NONE, p1, e1, IrAttrs.INLINE, IrLinkage.INTERNAL)
        }
        for (family in typeCheckPlan.families) {
            b.declareDefined(family.helperName, IrType.I32, IrExt.NONE, p1, e1, IrAttrs.PURE_INLINE, IrLinkage.INTERNAL)
        }
    }

    /** The IR name of a function of the module (the user's `main` is a helper the generated `mk_main` calls). */
    fun fname(cName: String): String = if (cName == Mangling.MAIN) userMainSymbol else cName

    /** A pooled NUL-terminated C string constant; the result is its global (a `ptr` operand). */
    fun cstr(text: String): V {
        val known = cstrings[text]
        if (known != null) return known
        val g = b.defineGlobal(IrSection.STRINGS, ".cs.${cstrings.size}", b.constCString(text), true, IrLinkage.PRIVATE, 0, true)
        cstrings[text] = g
        return g
    }

    /** A pooled Mini string literal: a static `MkString` object with UTF-16 chars. */
    fun literal(value: String): V {
        val known = literals[value]
        if (known != null) return known
        val items = ArrayList<V>()
        for (ch in value) items.add(b.constInt(IrType.I16, ch.code.toLong()))
        items.add(b.constInt(IrType.I16, 0L))
        val init = b.constPackedStruct(
            listOf(
                b.externTypeInfo("mk_type_String"),
                b.constInt(IrType.I32, 0L),
                b.constInt(IrType.I32, 1L),
                b.constInt(IrType.I32, value.length.toLong()),
                b.constArray(IrType.I16, items),
            ),
        )
        val g = b.defineGlobal(IrSection.LITERALS, "kstr_${literals.size}", init, true, IrLinkage.INTERNAL, 8, false)
        literals[value] = g
        return g
    }

    // ---- types ----------------------------------------------------------------------

    fun ity(t: MirType): IrType = when (t) {
        TInt -> IrType.I32
        TLong -> IrType.I64
        TBoolean -> IrType.I1
        TDouble -> IrType.DOUBLE
        TChar -> IrType.I16
        TByte -> IrType.I8
        TShort -> IrType.I16
        TFloat -> IrType.FLOAT
        is TPtr -> IrType.PTR
        TString, TNString -> IrType.PTR
        TUnit, TNothing -> IrType.VOID
        TNull, is TObject, is TBoxed -> IrType.PTR
    }

    /** The ABI attribute of an argument / result of type [t] (C `bool` and `uint16_t` are zero-extended). */
    fun ext(t: MirType): IrExt = if (t === TBoolean || t === TChar) IrExt.ZERO else if (t === TByte || t === TShort) IrExt.SIGN else IrExt.NONE
    fun rext(t: MirType): IrExt = ext(t)

    fun exts(ts: List<MirType>): List<IrExt> = ts.map { ext(it) }

    fun zero(t: MirType): V = when (t) {
        TDouble -> b.constFloat(IrType.DOUBLE, 0.0)
        TFloat -> b.constFloat(IrType.FLOAT, 0.0)
        TBoolean -> b.constInt(IrType.I1, 0L)
        TInt, TLong, TChar, TByte, TShort -> b.constInt(ity(t), 0L)
        else -> b.constNull()
    }

    /** The type info of the class [cName]: the module's own, or an external declaration. */
    fun tiOfClass(cName: String): V =
        if (classes[cName]?.external == false) b.global(cName + "_ti") else b.externTypeInfo(cName + "_ti")

    fun typeInfoOf(t: MirType): V = when (t) {
        TString, TNString -> b.externTypeInfo("mk_type_String")
        is TObject -> if (t.cName == ANY_CNAME) b.externTypeInfo("mk_type_Any") else tiOfClass(t.cName)
        is TBoxed -> b.externTypeInfo(boxTypeInfo(t.prim))
        else -> throw EmitError("no type info for $t")
    }

    fun boxTypeInfo(p: MirType): String = when (p) {
        TInt -> "mk_type_Int"
        TLong -> "mk_type_Long"
        TBoolean -> "mk_type_Boolean"
        TDouble -> "mk_type_Double"
        TFloat -> "mk_type_Float"
        TChar -> "mk_type_Char"
        else -> throw EmitError("no box for $p")
    }

    fun boxName(p: MirType): String = when (p) {
        TInt -> "int"
        TLong -> "long"
        TBoolean -> "boolean"
        TDouble -> "double"
        TFloat -> "float"
        TChar -> "char"
        else -> throw EmitError("no box for $p")
    }

    /** Declares the runtime function [name] from the MIR types (first use wins) and returns its address. */
    fun declare(name: String, ret: MirType, params: List<MirType>, attrs: IrAttrs = IrAttrs.STD): V =
        b.declareExternal(name, ity(ret), rext(ret), params.map { ity(it) }, exts(params), attrs, false)

    /** A vtable / itable entry that is not a function of this module is a runtime function (only its address is taken). */
    fun declareIfExternal(name: String) {
        if (!sig.containsKey(name) && !b.hasFunction(name)) {
            b.declareExternal(name, IrType.PTR, IrExt.NONE, listOf(IrType.PTR), listOf(IrExt.NONE), IrAttrs.STD, false)
        }
    }

    // ---- emission primitives -------------------------------------------------------------

    /** `mk_pending_exception` (an `MkObject *`) loaded into a fresh temporary. */
    fun loadPending(): V {
        openBlock()
        if (target.pendingAccessor) {
            // targets without TLS relocations in generated code (the in-process JIT): the runtime hands out the slot's address
            val f = declare("mk_pending_slot", TString, emptyList(), IrAttrs.PURE)
            val slot = call(TString, f, emptyList(), emptyList())
            return b.loadTagged(IrType.PTR, slot, "pending exception", "")
        }
        return b.loadTagged(IrType.PTR, b.global("mk_pending_exception"), "pending exception", "")
    }

    fun newLabel(prefix: String): B {
        val n = prefix + lblCounter
        lblCounter++
        return b.newBlock(n)
    }

    fun namedBlock(name: String): B {
        val known = namedBlocks[name]
        if (known != null) return known
        val blk = b.newBlock(name)
        namedBlocks[name] = blk
        return blk
    }

    fun openBlock() {
        if (terminated) startBlock(newLabel("dead"))
    }

    fun br(label: B) {
        if (!terminated) {
            b.br(label)
            terminated = true
        }
    }

    /** Starts block [label]; the previous one falls into it with a `br` unless it ended already. */
    fun startBlock(label: B) {
        if (!terminated) b.br(label)
        b.appendBlock(label)
        terminated = false
        curBlock = label
    }

    fun condBr(c: V, a: B, e: B, weights: Boolean = false) {
        openBlock()
        b.condBr(c, a, e, weights)
        terminated = true
    }

    fun ret(v: V) {
        openBlock()
        b.ret(v)
        terminated = true
    }

    fun retVoid() {
        openBlock()
        b.retVoid()
        terminated = true
    }

    fun unreachable() {
        openBlock()
        b.unreachable()
        terminated = true
    }

    fun call(ret: MirType, callee: V, types: List<MirType>, args: List<V>): V {
        openBlock()
        return b.call(callee, ity(ret), rext(ret), args, exts(types), -1, "")
    }

    /** Call of a libminirt function with explicit MIR types. */
    fun rt(name: String, ret: MirType, types: List<MirType>, args: List<V>): V {
        val attrs = if (
            !customIsInstanceSymbol && name == "mk_is_instance" && ret === TBoolean && types.size == 2 &&
                types[0] === TString && types[1] === TString
        ) IrAttrs.READONLY else IrAttrs.STD
        val f = declare(name, ret, types, attrs)
        return call(ret, f, types, args)
    }

    fun voidCall(name: String, a: V) {
        rt(name, TUnit, listOf(TString), listOf(a))
    }

    fun gep(base: V, off: Int): V {
        if (off == 0) return base
        openBlock()
        return b.gep(base, b.constInt(IrType.I64, off.toLong()), true, "")
    }

    fun load(t: MirType, p: V): V {
        openBlock()
        return b.load(ity(t), p, 0, "")
    }

    fun store(v: V, p: V) {
        openBlock()
        b.store(v, p, 0)
    }

    /** [load] / [store] carrying the alias tag of a field or of an array part (see [IrBuilder.loadTagged]). */
    fun loadTag(t: MirType, p: V, tag: String): V {
        openBlock()
        return b.loadTagged(ity(t), p, tag, "")
    }

    fun storeTag(v: V, p: V, tag: String) {
        openBlock()
        b.storeTagged(v, p, tag)
    }

    fun fieldTag(owner: String, field: String): String = "f " + owner + "." + field

    /** C interop: a load from memory the program does not own the layout of: any alignment (a C `bool` is a byte). */
    fun loadAt(t: MirType, p: V): V {
        openBlock()
        if (t === TBoolean) {
            val x = b.load(IrType.I8, p, 1, "")
            return b.cmp(IrCmp.INE, x, b.constInt(IrType.I8, 0L), "")
        }
        return b.load(ity(t), p, 1, "")
    }

    fun storeAt(t: MirType, v: V, p: V) {
        openBlock()
        if (t === TBoolean) {
            val x = b.cast(IrCast.ZEXT, v, IrType.I8, "")
            b.store(x, p, 1)
            return
        }
        b.store(v, p, 1)
    }

    /** The default argument promotion of a variadic C call: Byte, Short, Boolean, Char to int, Float to double. */
    fun promote(t: MirType, v: V): Pair<MirType, V> {
        return when (t) {
            TByte, TShort -> TInt to cvt(IrCast.SEXT, v, IrType.I32)
            TBoolean -> TInt to cvt(IrCast.ZEXT, v, IrType.I32)
            TChar -> TInt to cvt(IrCast.ZEXT, v, IrType.I32)
            TFloat -> TDouble to cvt(IrCast.FPEXT, v, IrType.DOUBLE)
            else -> t to v
        }
    }

    /** Call of a C function of the program ([MirExpr.CCall]): declared once per symbol, the call carries its own signature. */
    fun cCall(x: MirExtern, args: List<MirExpr>): V {
        val vs = args.map { expr(it) }
        val callee = b.declareExternal(
            x.symbol, ity(x.returnType), rext(x.returnType), x.prototypeParams.map { ity(it) }, exts(x.prototypeParams), IrAttrs.NONE, x.isVariadic,
        )
        if (!x.isVariadic) return call(x.returnType, callee, x.params, vs)
        val argv = ArrayList<V>()
        val argx = ArrayList<IrExt>()
        for (k in vs.indices) {
            if (k < x.fixedParams) {
                argv.add(vs[k])
                argx.add(ext(x.params[k]))
            } else {
                val pr = promote(x.params[k], vs[k])
                argv.add(pr.second)
                argx.add(ext(pr.first))
            }
        }
        openBlock()
        return b.call(callee, ity(x.returnType), rext(x.returnType), argv, argx, x.fixedParams, "")
    }

    fun binop(op: IrOp, a: V, c: V): V {
        openBlock()
        return b.bin(op, a, c, "")
    }

    fun icmp(p: IrCmp, a: V, c: V): V {
        openBlock()
        return b.cmp(p, a, c, "")
    }

    fun cvt(op: IrCast, a: V, to: IrType): V {
        openBlock()
        return b.cast(op, a, to, "")
    }

    fun c32(v: Int): V = b.constInt(IrType.I32, v.toLong())
    fun c64(v: Int): V = b.constInt(IrType.I64, v.toLong())

    // ---- reference counting -------------------------------------------------------------

    /** Release of a reference that may be NULL; with RcOpt the NULL test and the fast path are inline. */
    fun rel(x: V) {
        if (inlineRc) call(TUnit, b.global("mkh_rel"), listOf(TString), listOf(x)) else relCall(x)
    }

    fun relCall(x: V) {
        val f = declare("mk_release", TUnit, listOf(TString))
        call(TUnit, f, listOf(TString), listOf(x))
    }

    fun retain(x: V) {
        if (inlineRc) call(TUnit, b.global("mkh_retain"), listOf(TString), listOf(x))
        else {
            val f = declare("mk_retain", TUnit, listOf(TString))
            call(TUnit, f, listOf(TString), listOf(x))
        }
    }

    // ---- the prelude: helpers that CEmitter's PRELUDE defines in C ------------------------------

    /** Starts a helper function of the prelude with all-pointer / integer parameters. */
    fun beginHelper(name: String, ret: IrType, pnames: List<String>, ptypes: List<IrType>, attrs: IrAttrs) {
        b.beginFunction(
            IrSection.PRELUDE, name, ret, IrExt.NONE, pnames, ptypes, ptypes.map { IrExt.NONE }, attrs, IrLinkage.INTERNAL,
        )
        curBlock = b.entryBlock()
        terminated = false
    }

    fun endHelper() {
        b.endFunction()
        b.blankLine(IrSection.PRELUDE)
    }

    fun emitPrelude() {
        val flagsOff = layouts.refcountOffset + 4
        val rcOff = layouts.refcountOffset
        val ptr = IrType.PTR
        val i32 = IrType.I32
        val nul = b.constNull()
        if (inlineRc) {
            val retain = declare("mk_retain", TUnit, listOf(TString))
            val release = declare("mk_release", TUnit, listOf(TString))
            for (isRetain in listOf(true, false)) {
                beginHelper(if (isRetain) "mkh_retain" else "mkh_rel", IrType.VOID, listOf("o"), listOf(ptr), IrAttrs.INLINE)
                val o = b.param(0)
                val done = b.newBlock("done")
                val nn = b.newBlock("nn")
                val chk = b.newBlock("chk")
                val upd = b.newBlock(if (isRetain) "inc" else "dec")
                val slow = b.newBlock("slow")
                val isnull = b.cmp(IrCmp.IEQ, o, nul, "isnull")
                b.condBr(isnull, done, nn, false)
                b.appendBlock(nn)
                val fp = b.gep(o, c64(flagsOff), true, "fp")
                val fl = b.load(i32, fp, 0, "fl")
                val sm = b.bin(IrOp.AND, fl, c32(3), "sm")
                val imm = b.cmp(IrCmp.IEQ, sm, c32(0), "imm")
                b.condBr(imm, chk, done, false)
                b.appendBlock(chk)
                if (isRetain) {
                    val sh = b.bin(IrOp.AND, fl, c32(4), "sh")
                    val nsh = b.cmp(IrCmp.IEQ, sh, c32(0), "nsh")
                    val rp = b.gep(o, c64(rcOff), true, "rp")
                    val rc = b.load(i32, rp, 0, "rc")
                    val rm1 = b.bin(IrOp.SUB, rc, c32(1), "rm1")
                    val ok = b.cmp(IrCmp.ULT, rm1, c32(-3), "ok")
                    val both = b.bin(IrOp.AND, nsh, ok, "both")
                    b.condBr(both, upd, slow, false)
                    b.appendBlock(upd)
                    val rc1 = b.bin(IrOp.ADD, rc, c32(1), "rc1")
                    b.store(rc1, rp, 0)
                    b.br(done)
                    b.appendBlock(slow)
                    b.call(retain, IrType.VOID, IrExt.NONE, listOf(o), listOf(IrExt.NONE), -1, "")
                    b.br(done)
                } else {
                    val sh = b.bin(IrOp.AND, fl, c32(4), "sh")
                    val nsh = b.cmp(IrCmp.IEQ, sh, c32(0), "nsh")
                    val rp = b.gep(o, c64(rcOff), true, "rp")
                    val rc = b.load(i32, rp, 0, "rc")
                    val gt = b.cmp(IrCmp.UGT, rc, c32(1), "gt")
                    val both = b.bin(IrOp.AND, nsh, gt, "both")
                    b.condBr(both, upd, slow, false)
                    b.appendBlock(upd)
                    val rc1 = b.bin(IrOp.SUB, rc, c32(1), "rc1")
                    b.store(rc1, rp, 0)
                    b.br(done)
                    b.appendBlock(slow)
                    b.call(release, IrType.VOID, IrExt.NONE, listOf(o), listOf(IrExt.NONE), -1, "")
                    b.br(done)
                }
                b.appendBlock(done)
                b.retVoid()
                endHelper()
            }
        }
        val abstractMsg = cstr("AbstractMethodError")
        val panic = declare("mk_panic", TNothing, listOf(TString), IrAttrs.NORETURN)
        beginHelper("mkh_abstract", IrType.VOID, emptyList(), emptyList(), IrAttrs.STD)
        b.call(panic, IrType.VOID, IrExt.NONE, listOf(abstractMsg), listOf(IrExt.NONE), -1, "")
        b.unreachable()
        endHelper()
        // checked / safe casts
        val npeMsg = cstr("null cannot be cast to non-null type")
        val raise = declare("mk_raise", TUnit, listOf(TString, TString))
        val checkedCast = declare("mk_checked_cast", TString, listOf(TString, TString))
        val npeTi = b.externTypeInfo("MkNullPointerException_ti")
        beginHelper("mkh_cast_nn", ptr, listOf("o", "t"), listOf(ptr, ptr), IrAttrs.STD)
        run {
            val o = b.param(0)
            val t = b.param(1)
            val isnull = b.newBlock("isnull")
            val ok = b.newBlock("ok")
            val n = b.cmp(IrCmp.IEQ, o, nul, "n")
            b.condBr(n, isnull, ok, false)
            b.appendBlock(isnull)
            b.call(raise, IrType.VOID, IrExt.NONE, listOf(npeTi, npeMsg), listOf(IrExt.NONE, IrExt.NONE), -1, "")
            b.ret(nul)
            b.appendBlock(ok)
            val r = b.call(checkedCast, ptr, IrExt.NONE, listOf(o, t), listOf(IrExt.NONE, IrExt.NONE), -1, "r")
            b.ret(r)
        }
        endHelper()
        beginHelper("mkh_nn", ptr, listOf("o"), listOf(ptr), IrAttrs.STD)
        run {
            val o = b.param(0)
            val isnull = b.newBlock("isnull")
            val ok = b.newBlock("ok")
            val n = b.cmp(IrCmp.IEQ, o, nul, "n")
            b.condBr(n, isnull, ok, false)
            b.appendBlock(isnull)
            b.call(raise, IrType.VOID, IrExt.NONE, listOf(npeTi, npeMsg), listOf(IrExt.NONE, IrExt.NONE), -1, "")
            b.ret(o)
            b.appendBlock(ok)
            b.ret(o)
        }
        endHelper()
        // Kotlin-exact integer division
        val dz = cstr("/ by zero")
        val raiseArith = declare("mk_raise_arith", TUnit, listOf(TString))
        for (w in listOf(IrType.I32, IrType.I64)) {
            val n = if (w == IrType.I32) "int" else "long"
            for (isDiv in listOf(true, false)) {
                beginHelper(if (isDiv) "mkh_div_$n" else "mkh_rem_$n", w, listOf("a", "b"), listOf(w, w), IrAttrs.STD)
                val a = b.param(0)
                val d = b.param(1)
                val zero = b.newBlock("zero")
                val nz = b.newBlock("nz")
                val neg = b.newBlock("neg")
                val div = b.newBlock("div")
                val z = b.cmp(IrCmp.IEQ, d, b.constInt(w, 0L), "z")
                b.condBr(z, zero, nz, false)
                b.appendBlock(zero)
                b.call(raiseArith, IrType.VOID, IrExt.NONE, listOf(dz), listOf(IrExt.NONE), -1, "")
                b.ret(b.constInt(w, 0L))
                b.appendBlock(nz)
                val m = b.cmp(IrCmp.IEQ, d, b.constInt(w, -1L), "m")
                b.condBr(m, neg, div, false)
                b.appendBlock(neg)
                if (isDiv) {
                    val ng = b.bin(IrOp.SUB, b.constInt(w, 0L), a, "ng")
                    b.ret(ng)
                } else {
                    b.ret(b.constInt(w, 0L))
                }
                b.appendBlock(div)
                val q = b.bin(if (isDiv) IrOp.SDIV else IrOp.SREM, a, d, "q")
                b.ret(q)
                endHelper()
            }
        }
    }

    // ---- classes ---------------------------------------------------------------------

    /** Starts a function the lowering generates (dispose glue, constructor wrappers): all state of a function is reset. */
    fun startFunction() {
        terminated = false
        lblCounter = 0
        scopes.clear()
        handlers.clear()
        caught.clear()
        dead.clear()
        pending.clear()
        usedLabels.clear()
        slots.clear()
        namedBlocks.clear()
        curStmt = null
        fplan = null
        curFn = null
    }

    fun beginGenerated(s: IrSection, name: String, ret: MirType, pnames: List<String>, ptypes: List<IrType>, pexts: List<IrExt>, linkage: IrLinkage) {
        startFunction()
        b.beginFunction(s, name, ity(ret), rext(ret), pnames, ptypes, pexts, IrAttrs.STD, linkage)
        curBlock = b.entryBlock()
    }

    /** Defines classifiers that compare only locally emitted canonical type-info addresses. */
    fun emitTypeCheckHelpers() {
        for (family in typeCheckPlan.families) {
            b.beginFunction(
                IrSection.PRELUDE, family.helperName, IrType.I32, IrExt.NONE, listOf("type"), listOf(IrType.PTR),
                listOf(IrExt.NONE), IrAttrs.PURE_INLINE, IrLinkage.INTERNAL,
            )
            val type = b.param(0)
            for ((index, tagClass) in family.tags.withIndex()) {
                val matched = b.newBlock("tag_${index + 1}")
                val next = b.newBlock("next_${index + 1}")
                val equal = b.cmp(IrCmp.IEQ, type, b.global(tagClass.cName + "_ti"), "")
                b.condBr(equal, matched, next, false)
                b.appendBlock(matched)
                b.ret(b.constInt(IrType.I32, (index + 1).toLong()))
                b.appendBlock(next)
            }
            b.ret(b.constInt(IrType.I32, 0L))
            b.endFunction()
        }
    }

    /** Unknown runtime type-info stays on mk_is_instance, preserving foreign subclass semantics. */
    private fun typeCheck(objectValue: V, expression: MirExpr.IsInstance, use: TypeCheckUse): V {
        val isNull = icmp(IrCmp.IEQ, objectValue, b.constNull())
        val nullBlock = newLabel("type_null")
        val readType = newLabel("type_read")
        val slow = newLabel("type_slow")
        val fast = newLabel("type_fast")
        val knownMerge = newLabel("type_known")
        val merge = newLabel("type_merge")
        condBr(isNull, nullBlock, readType, true)

        startBlock(nullBlock)
        val nullResult = b.constInt(IrType.I1, 0L)
        val nullEnd = curBlock
        br(merge)

        startBlock(readType)
        val actualType = load(TString, gep(objectValue, 0))
        val tag = call(TInt, b.global(use.helperName), listOf(TString), listOf(actualType))
        val unknown = icmp(IrCmp.IEQ, tag, c32(0))
        condBr(unknown, slow, fast, true)

        startBlock(slow)
        val fallback = rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(objectValue, typeInfoOf(expression.target)))
        val slowEnd = curBlock
        br(knownMerge)

        startBlock(fast)
        val bitIndex = binop(IrOp.SUB, tag, c32(1))
        val bit = binop(IrOp.SHL, c32(1), bitIndex)
        val selected = binop(IrOp.AND, bit, c32(use.targetMask))
        val result = icmp(IrCmp.INE, selected, c32(0))
        val fastEnd = curBlock
        br(knownMerge)

        startBlock(knownMerge)
        val knownResult = b.phi(IrType.I1, fallback, slowEnd, result, fastEnd)
        val knownEnd = curBlock
        br(merge)

        startBlock(merge)
        return b.phi(IrType.I1, nullResult, nullEnd, knownResult, knownEnd)
    }

    fun emitClasses() {
        val classLinkage = if (externalClasses) IrLinkage.EXTERNAL else IrLinkage.INTERNAL
        for (c in module.classes) {
            if (isExternalClass(c)) {
                b.externTypeInfo(c.cName + "_ti")
                continue
            }
            b.comment(IrSection.CLASSES, "${if (c.isInterface) "interface" else "class"} ${comment(c.qualifiedName)}")
            val nameSym = b.defineGlobal(IrSection.CLASSES, "${c.cName}_name", b.constCString(c.qualifiedName), true, IrLinkage.PRIVATE, 0, true)
            var ifs: V = b.constNull()
            if (c.interfaces.isNotEmpty()) {
                ifs = b.defineGlobal(
                    IrSection.CLASSES, "${c.cName}_ifs", b.constArray(IrType.PTR, c.interfaces.map { tiOfClass(it) }), true, IrLinkage.INTERNAL, 0, false,
                )
            }
            if (c.isInterface) {
                val init = b.constNamedStruct(
                    "MkTypeInfo",
                    listOf(nameSym, b.constInt(szT, 0L), b.constNull(), b.constNull(), b.constNull(), b.constInt(szT, 0L), ifs, b.constInt(szT, c.interfaces.size.toLong())),
                )
                b.defineGlobal(IrSection.CLASSES, "${c.cName}_ti", init, true, if (c.cName in exportedClassNames) IrLinkage.EXTERNAL else classLinkage, 8, false)
                b.blankLine(IrSection.CLASSES)
                continue
            }
            val sup = c.superCName?.let { classes[it] ?: throw EmitError("unknown superclass $it") }
            val lay = layouts.of(c.cName)
            // dispose
            val refFields = c.fields.filter { tr.rcField(c, it) }
            // owned fields of tracked type (Strict classes): freeing the object frees them
            val ownedFields = c.fields.filter { tr.ownedTrackedField(c, it) }
            var dispose: V = b.constNull()
            if (refFields.isNotEmpty() || ownedFields.isNotEmpty()) {
                beginGenerated(IrSection.CLASSES, "${c.cName}_dispose", TUnit, listOf("self"), listOf(IrType.PTR), listOf(IrExt.NONE), IrLinkage.INTERNAL)
                val self = b.param(0)
                for (f in ownedFields) {
                    val v = load(TString, gep(self, layouts.fieldOffset(c.cName, f.name)))
                    voidCall("mk_free_owned", v)
                }
                for (f in refFields) {
                    val v = load(TString, gep(self, layouts.fieldOffset(c.cName, f.name)))
                    rel(v)
                }
                retVoid()
                b.endFunction()
                dispose = b.global("${c.cName}_dispose")
            }
            // vtable (three Any slots, user slots, one pointer per itable) and itables
            val vt = ArrayList<V>()
            val anyDefaults = listOf("mk_any_to_string", "mk_any_equals", "mk_any_hash_code")
            for ((i, e) in c.vtable.withIndex()) {
                if (i < 3 && e == null) {
                    when (i) {
                        0 -> declare("mk_any_to_string", TString, listOf(TString))
                        1 -> declare("mk_any_equals", TBoolean, listOf(TString, TString))
                        else -> declare("mk_any_hash_code", TInt, listOf(TString))
                    }
                }
                if (e != null) declareIfExternal(e)
                vt.add(b.global(e ?: if (i < 3) anyDefaults[i] else "mkh_abstract"))
            }
            for ((k, it) in c.itables.withIndex()) {
                for (e in it.entries) if (e != null) declareIfExternal(e)
                val entries = if (it.entries.isEmpty()) listOf(b.constNull()) else it.entries.map { e -> b.global(e ?: "mkh_abstract") }
                val itg = b.defineGlobal(IrSection.CLASSES, "${c.cName}_it$k", b.constArray(IrType.PTR, entries), true, IrLinkage.INTERNAL, 0, false)
                vt.add(itg)
            }
            val vtg = b.defineGlobal(IrSection.CLASSES, "${c.cName}_vt", b.constArray(IrType.PTR, vt), true, IrLinkage.INTERNAL, 0, false)
            val superTi = if (sup == null) b.externTypeInfo("mk_type_Any") else tiOfClass(sup.cName)
            val init = b.constNamedStruct(
                "MkTypeInfo",
                listOf(
                    nameSym, b.constInt(szT, lay.size.toLong()), superTi, dispose, vtg, b.constInt(szT, vt.size.toLong()), ifs,
                    b.constInt(szT, c.interfaces.size.toLong()),
                ),
            )
            val typeInfoLinkage = if (c.cName in exportedClassNames) IrLinkage.EXTERNAL else classLinkage
            b.defineGlobal(IrSection.CLASSES, "${c.cName}_ti", init, true, typeInfoLinkage, 8, false)
            if (c.objectCtor != null) {
                val obj = b.defineGlobal(IrSection.CLASSES, "${c.cName}_obj", b.constNull(), false, classLinkage, 0, false)
                val alloc = declare("mk_alloc", TString, listOf(TString))
                beginGenerated(IrSection.CLASSES, "${c.cName}_get", TString, emptyList(), emptyList(), emptyList(), if (c.cName in exportedClassNames) IrLinkage.EXTERNAL else classLinkage)
                val init0 = b.newBlock("init")
                val done = b.newBlock("done")
                val o = b.load(IrType.PTR, obj, 0, "o")
                val n = b.cmp(IrCmp.IEQ, o, b.constNull(), "n")
                b.condBr(n, init0, done, false)
                b.appendBlock(init0)
                val a = b.call(alloc, IrType.PTR, IrExt.NONE, listOf(b.global("${c.cName}_ti")), listOf(IrExt.NONE), -1, "a")
                b.store(a, obj, 0)
                b.call(b.global(fname(c.objectCtor)), IrType.VOID, IrExt.NONE, listOf(a), listOf(IrExt.NONE), -1, "")
                b.br(done)
                b.appendBlock(done)
                val r = b.load(IrType.PTR, obj, 0, "r")
                b.ret(r)
                b.endFunction()
            }
            b.blankLine(IrSection.CLASSES)
        }
        // allocating constructor wrappers
        for (f in module.functions) {
            val cls = f.constructorOf ?: continue
            val ps = f.params.drop(1)
            val pnames = ps.indices.map { "a$it" }
            val ptypes = ps.map { ity(it.type) }
            val pexts = ps.map { ext(it.type) }
            val alloc = declare("mk_alloc", TString, listOf(TString))
            beginGenerated(IrSection.CLASSES, "${f.cName}_new", TString, pnames, ptypes, pexts, IrLinkage.EXTERNAL)
            val a = call(TString, alloc, listOf(TString), listOf(b.global("${cls}_ti")))
            val argv = ArrayList<V>()
            val argt = ArrayList<MirType>()
            argv.add(a); argt.add(TString)
            for ((i, p) in ps.withIndex()) { argv.add(b.param(i)); argt.add(p.type) }
            call(TUnit, b.global(f.cName), argt, argv)
            if (f.cName in throwing.mayThrow) {
                val p = loadPending()
                val c = icmp(IrCmp.INE, p, b.constNull())
                val fail = b.newBlock("fail")
                val ok = b.newBlock("ok")
                condBr(c, fail, ok, true)
                startBlock(fail)
                relCall(a)
                ret(b.constNull())
                startBlock(ok)
            }
            ret(a)
            b.endFunction()
            b.blankLine(IrSection.CLASSES)
            if (f.cName in newaUsed) {
                // the same through a chosen allocator (Strict code); `al` is the address of the MkAllocator (0: the default one)
                beginGenerated(IrSection.CLASSES, "${f.cName}_newa", TString, listOf("al") + pnames, listOf(IrType.I64) + ptypes, listOf(IrExt.NONE) + pexts, IrLinkage.EXTERNAL)
                var alp = b.param(0)
                if (pb == 4) alp = cvt(IrCast.TRUNC, alp, IrType.I32)
                val ap = cvt(IrCast.INTTOPTR, alp, IrType.PTR)
                val allocWith = declare("mk_alloc_with", TString, listOf(TString, TString))
                val a2 = call(TString, allocWith, listOf(TString, TString), listOf(ap, b.global("${cls}_ti")))
                val isNull = icmp(IrCmp.IEQ, a2, b.constNull())
                val oom = b.newBlock("oom")
                val allocB = b.newBlock("alloc")
                condBr(isNull, oom, allocB, true)
                startBlock(oom)
                val panic = declare("mk_panic", TNothing, listOf(TString), IrAttrs.NORETURN)
                call(TUnit, panic, listOf(TString), listOf(cstr("out of memory")))
                unreachable()
                startBlock(allocB)
                val argv2 = ArrayList<V>()
                val argt2 = ArrayList<MirType>()
                argv2.add(a2); argt2.add(TString)
                for ((i, p) in ps.withIndex()) { argv2.add(b.param(i + 1)); argt2.add(p.type) }
                call(TUnit, b.global(f.cName), argt2, argv2)
                if (f.cName in throwing.mayThrow) {
                    val pe = loadPending()
                    val c2 = icmp(IrCmp.INE, pe, b.constNull())
                    val fail = b.newBlock("fail")
                    val ok = b.newBlock("ok")
                    condBr(c2, fail, ok, true)
                    startBlock(fail)
                    voidCall("mk_discard", a2)
                    ret(b.constNull())
                    startBlock(ok)
                }
                ret(a2)
                b.endFunction()
                b.blankLine(IrSection.CLASSES)
            }
        }
    }

    // ---- functions ---------------------------------------------------------------------

    fun comment(s: String): String = s.replace('\n', ' ').replace('\r', ' ')

    fun lname(l: MirLocal): String = Mangling.local(l.name, l.id)

    /** The stack slot of [l]; created on first use (all allocas live in the entry block). */
    fun slot(l: MirLocal): V {
        val known = slots[l.id]
        if (known != null) return known
        val s = b.alloca(ity(l.type), lname(l))
        slots[l.id] = s
        return s
    }

    fun emitFunction(f: MirFunction) {
        startFunction()
        currentReturn = f.returnType
        fplan = rcPlan?.of(f)
        curFn = f
        b.comment(IrSection.FUNCTIONS, comment(f.qualifiedName))
        b.beginFunction(
            IrSection.FUNCTIONS, fname(f.cName), ity(f.returnType), rext(f.returnType),
            f.params.map { "in_" + Mangling.local(it.name, it.id) }, f.params.map { ity(it.type) }, f.params.map { ext(it.type) },
            IrAttrs.STD, IrLinkage.EXTERNAL,
        )
        curBlock = b.entryBlock()
        for ((i, p) in f.params.withIndex()) {
            store(b.param(i), slot(p))
        }
        scope(f.body, ownedParams = fplan?.ownedParams ?: emptyList())
        if (!terminated) {
            if (f.returnType === TUnit || f.returnType === TNothing) {
                retVoid()
            } else {
                val safe = f.qualifiedName.filter { it.isLetterOrDigit() || it == '.' || it == '_' }
                val panic = declare("mk_panic", TNothing, listOf(TString), IrAttrs.NORETURN)
                call(TUnit, panic, listOf(TString), listOf(cstr("unreachable: control reached the end of $safe")))
                unreachable()
            }
        }
        b.endFunction()
        b.blankLine(IrSection.FUNCTIONS)
    }

    // ---- WebAssembly libraries (WasmLib.kt) --------------------------------------------------------

    /** The carrier MIR type of the wasm value [k] (what [ity] turns into the IR type of the wasm parameter or result). */
    fun wasmCarrier(k: WasmKind): MirType = when (k) {
        WasmKind.I32, WasmKind.BOOL -> TInt
        WasmKind.I64 -> TLong
        WasmKind.F32 -> TFloat
        WasmKind.F64 -> TDouble
        WasmKind.UNIT -> TUnit
        else -> TString
    }

    fun emitWasmLib() {
        b.comment(IrSection.FUNCTIONS, "WebAssembly library: the exported functions (the wrappers convert arguments and results, see WasmLib.kt)")
        for (x in module.wasmExports) emitWasmExport(x)
        emitWasmInit()
    }

    fun emitWasmExport(x: MirWasmExport) {
        val f = sig[x.cName] ?: throw EmitError("exported function " + x.cName + " is missing")
        val pnames = ArrayList<String>()
        val ptypes = ArrayList<IrType>()
        val pexts = ArrayList<IrExt>()
        val kinds = ArrayList<WasmKind>()
        for ((i, p) in f.params.withIndex()) {
            val k = WasmAbi.kindOf(p.type) ?: throw EmitError("exported function " + x.name + ": parameter " + p.name + " has the type " + p.type.displayName + " which cannot be exported")
            kinds.add(k)
            if (WasmAbi.wasmParams(k) == 2) {
                pnames.add("w" + i + "p"); ptypes.add(IrType.PTR); pexts.add(IrExt.NONE)
                pnames.add("w" + i + "n"); ptypes.add(IrType.I32); pexts.add(IrExt.NONE)
            } else {
                val c = wasmCarrier(k)
                pnames.add("w" + i); ptypes.add(ity(c)); pexts.add(ext(c))
            }
        }
        val rk = WasmAbi.kindOf(f.returnType) ?: throw EmitError("exported function " + x.name + ": the result type " + f.returnType.displayName + " cannot be exported")
        val carrier = wasmCarrier(rk)
        beginGenerated(IrSection.FUNCTIONS, "mkw_" + x.name, carrier, pnames, ptypes, pexts, IrLinkage.EXTERNAL)
        b.exportCurrentFunction(x.name)
        val args = ArrayList<V>()
        val created = ArrayList<V>()
        var pi = 0
        for ((i, p) in f.params.withIndex()) {
            val k = kinds[i]
            when {
                k == WasmKind.STRING -> {
                    val s = rt("mk_string_from_utf8", TString, listOf(TString, TInt), listOf(b.param(pi), b.param(pi + 1)))
                    args.add(s); created.add(s)
                }
                WasmAbi.isArray(k) -> {
                    val a = rt("mk_wasm_array_in", TString, listOf(TInt, TString, TInt), listOf(b.constInt(IrType.I32, WasmAbi.arrayCode(k).toLong()), b.param(pi), b.param(pi + 1)))
                    args.add(a); created.add(a)
                }
                k == WasmKind.BOOL -> args.add(icmp(IrCmp.INE, b.param(pi), b.constInt(IrType.I32, 0L)))
                else -> args.add(b.param(pi))
            }
            pi += WasmAbi.wasmParams(k)
        }
        val r = call(f.returnType, b.global(fname(f.cName)), f.params.map { it.type }, args)
        for (c in created) relCall(c)
        val failed = rt("mk_wasm_failed", TBoolean, emptyList(), emptyList())
        val bad = b.newBlock("failed")
        val good = b.newBlock("ok")
        condBr(failed, bad, good, true)
        startBlock(bad)
        if (rk == WasmKind.UNIT) retVoid() else ret(zero(carrier))
        startBlock(good)
        when {
            rk == WasmKind.UNIT -> retVoid()
            rk == WasmKind.BOOL -> ret(cvt(IrCast.ZEXT, r, IrType.I32))
            rk == WasmKind.STRING -> ret(rt("mk_wasm_string_out", TString, listOf(TString), listOf(r)))
            WasmAbi.isArray(rk) -> ret(rt("mk_wasm_array_out", TString, listOf(TString, TInt), listOf(r, b.constInt(IrType.I32, WasmAbi.arrayCode(rk).toLong()))))
            else -> ret(r)
        }
        b.endFunction()
        b.blankLine(IrSection.FUNCTIONS)
    }

    /** `mini_init`: initialises the runtime and runs the top-level property initialisers (the part of `main` that is not user code). */
    fun emitWasmInit() {
        beginGenerated(IrSection.FUNCTIONS, "mkw_init", TUnit, emptyList(), emptyList(), emptyList(), IrLinkage.EXTERNAL)
        b.exportCurrentFunction("mini_init")
        rt("mk_runtime_init", TUnit, emptyList(), emptyList())
        val facades = module.functions.firstOrNull { it.cName == WASM_INIT_FACADES }
        if (facades != null) {
            call(TUnit, b.global(fname(facades.cName)), emptyList(), emptyList())
            rt("mk_wasm_failed", TBoolean, emptyList(), emptyList())
        }
        retVoid()
        b.endFunction()
        b.blankLine(IrSection.FUNCTIONS)
    }

    fun emitMainWrapper(userMain: MirFunction) {
        b.comment(IrSection.FUNCTIONS, "program entry: an exception that escaped main is reported by the runtime")
        beginGenerated(IrSection.FUNCTIONS, entrySymbol, TUnit, emptyList(), emptyList(), emptyList(), IrLinkage.EXTERNAL)
        val uncaught = declare("mk_report_uncaught", TNothing, listOf(), IrAttrs.NORETURN)
        val um = b.global(userMainSymbol)
        if (userMain.params.isEmpty()) {
            call(TUnit, um, emptyList(), emptyList())
        } else {
            val args = declare("mk_b1_args", TString, listOf())
            val a = call(TString, args, emptyList(), emptyList())
            call(TUnit, um, listOf(TString), listOf(a))
            relCall(a)
        }
        val p = loadPending()
        val c = icmp(IrCmp.INE, p, b.constNull())
        val unc = b.newBlock("unc")
        val fin = b.newBlock("fin")
        condBr(c, unc, fin, true)
        startBlock(unc)
        call(TUnit, uncaught, emptyList(), emptyList())
        unreachable()
        startBlock(fin)
        retVoid()
        b.endFunction()
        b.blankLine(IrSection.FUNCTIONS)
    }

    // ---- scopes / reference counting ------------------------------------------------------------

    fun isTerminator(s: MirStmt?): Boolean =
        s is MirStmt.Return || s is MirStmt.Break || s is MirStmt.Continue || s is MirStmt.Panic || s is MirStmt.Throw

    fun refDecls(list: List<MirStmt>): List<MirLocal> =
        list.filterIsInstance<MirStmt.Declare>().map { it.local }.filter { rcL(it) }

    fun isBorrowed(l: MirLocal): Boolean = fplan?.isBorrowed(l) == true

    fun isStaticValue(e: MirExpr): Boolean = e is MirExpr.StringConst || e is MirExpr.NullConst

    fun scope(
        body: List<MirStmt>,
        loop: MirLabel? = null,
        extra: List<MirStmt> = emptyList(),
        epilogue: Boolean = true,
        mid: (() -> Unit)? = null,
        ownedParams: List<MirLocal> = emptyList(),
    ) {
        val all = refDecls(body) + refDecls(extra)
        for (l in all) store(b.constNull(), slot(l))
        val locals = if (fplan == null) all else ownedParams + all.filter { !isBorrowed(it) }
        scopes.add(Scope(locals, loop))
        if (rcPlan != null) for (l in all) pending.add(l.id)
        for (s in body) stmt(s)
        if (mid != null) mid()
        if (epilogue && (mid != null || !isTerminator(body.lastOrNull()))) releases(scopes.size - 1, scopes.size - 1, null, false)
        scopes.removeAt(scopes.size - 1)
    }

    /** The locals released when leaving scopes [from] down to [to] (skipping [keep], dead and not yet declared ones). */
    fun releaseList(from: Int, to: Int, keep: MirLocal?): List<MirLocal> {
        val r = ArrayList<MirLocal>()
        for (i in from downTo to) {
            for (l in scopes[i].locals.asReversed()) {
                if (l === keep || l.id in dead || l.id in pending) continue
                r.add(l)
            }
        }
        return r
    }

    fun releaseAll(list: List<MirLocal>, cold: Boolean) {
        for (l in list) {
            val v = load(TString, slot(l))
            if (cold) relCall(v) else rel(v)
        }
    }

    fun releases(from: Int, to: Int, keep: MirLocal?, cold: Boolean) {
        releaseAll(releaseList(from, to, keep), cold)
    }

    // ---- statements ----------------------------------------------------------------------

    fun stmt(s: MirStmt) {
        val saved = curStmt
        curStmt = s
        stmtInner(s)
        curStmt = saved
        if (s is MirStmt.Declare) pending.remove(s.local.id)
        val gone = fplan?.dead?.get(s)
        if (gone != null) for (l in gone) dead.add(l.id)
    }

    fun closedFor(s: MirStmt?, l: MirLocal): Boolean = s != null && fplan?.dead?.get(s)?.any { it === l } == true

    fun stmtInner(s: MirStmt) {
        when (s) {
            is MirStmt.Declare -> {
                val t = s.local.type
                if (t === TUnit || t === TNothing) throw EmitError("local of type $t")
                if (rcL(s.local)) {
                    val init = s.init ?: return
                    val v = expr(init, true)
                    store(v, slot(s.local))
                    val moved = fplan?.moves?.get(s)
                    if (!ownedE(init) && !isStaticValue(init) && moved == null && !isBorrowed(s.local)) retain(v)
                    if (moved != null && !closedFor(s, moved)) store(b.constNull(), slot(moved))
                } else {
                    val v = if (s.init != null) expr(s.init, true) else zero(t)
                    store(v, slot(s.local))
                }
            }
            is MirStmt.Assign -> {
                val l = s.local
                if (rcL(l) && isBorrowed(l)) {
                    store(expr(s.value, true), slot(l))
                } else if (rcL(l) && fplan?.selfTakes?.contains(s) == true) {
                    val v = expr(s.value, true)
                    store(v, slot(l))
                    if (!ownedE(s.value) && !isStaticValue(s.value)) retain(v)
                } else if (rcL(l)) {
                    val moved = fplan?.moves?.get(s)
                    val n = expr(s.value, true)
                    if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) retain(n)
                    val old = load(TString, slot(l))
                    store(n, slot(l))
                    rel(old)
                    if (moved != null && !closedFor(s, moved)) store(b.constNull(), slot(moved))
                } else {
                    store(expr(s.value, true), slot(l))
                }
            }
            is MirStmt.SetField -> {
                val off = layouts.fieldOffset(s.owner, s.field)
                if (tr.rcStore(curFn, s)) {
                    val moved = fplan?.moves?.get(s)
                    val n = expr(s.value, true)
                    if (!ownedE(s.value) && !isStaticValue(s.value) && moved == null) retain(n)
                    val p = gep(expr(s.obj), off)
                    val tag = fieldTag(s.owner, s.field)
                    if (fplan?.fresh?.contains(s) == true) {
                        storeTag(n, p, tag)
                    } else {
                        val old = loadTag(TString, p, tag)
                        storeTag(n, p, tag)
                        rel(old)
                    }
                    if (moved != null && !closedFor(s, moved)) store(b.constNull(), slot(moved))
                } else {
                    val v = expr(s.value, true)
                    storeTag(v, gep(expr(s.obj), off), fieldTag(s.owner, s.field))
                }
            }
            is MirStmt.Store -> {
                val p = expr(s.ptr)
                val v = expr(s.value)
                storeAt(s.value.type, v, p)
            }
            is MirStmt.Eval -> {
                val e = s.expr
                if (e === MirExpr.UnitValue) return
                if (e.type === TUnit || e.type === TNothing) expr(e, true)
                else if (ownedE(e)) rel(expr(e, true))
                else expr(e)
            }
            is MirStmt.If -> {
                val thenL = newLabel("then")
                val elseL = newLabel("else")
                val endL = newLabel("endif")
                val c = expr(s.cond)
                condBr(c, thenL, if (s.elseBody.isEmpty()) endL else elseL)
                startBlock(thenL)
                scope(s.thenBody)
                br(endL)
                if (s.elseBody.isNotEmpty()) {
                    startBlock(elseL)
                    scope(s.elseBody)
                    br(endL)
                }
                startBlock(endL)
            }
            is MirStmt.Block -> scope(s.body)
            is MirStmt.While -> whileLoop(s)
            is MirStmt.Loop -> infiniteLoop(s)
            is MirStmt.DoWhile -> doWhile(s)
            is MirStmt.ForRange -> forRange(s)
            is MirStmt.Break -> {
                usedLabels.add(brk(s.label))
                releases(scopes.size - 1, loopIndex(s.label), null, false)
                br(namedBlock(brk(s.label)))
            }
            is MirStmt.Continue -> {
                usedLabels.add(cont(s.label))
                releases(scopes.size - 1, loopIndex(s.label) + 1, null, false)
                br(namedBlock(cont(s.label)))
            }
            is MirStmt.Panic -> {
                if (s.message.any { it < ' ' || it > '~' || it == '"' || it == '\\' }) throw EmitError("unsafe panic message")
                val panic = declare("mk_panic", TNothing, listOf(TString), IrAttrs.NORETURN)
                call(TUnit, panic, listOf(TString), listOf(cstr(s.message)))
                unreachable()
            }
            is MirStmt.Return -> returnStmt(s)
            is MirStmt.Throw -> {
                val v = expr(s.value)
                voidCall("mk_throw", v)
                exitStmt()
            }
            is MirStmt.Try -> tryStmt(s)
        }
    }

    // ---- exceptions ----------------------------------------------------------------------

    fun returnDefault() {
        when (currentReturn) {
            TUnit, TNothing -> retVoid()
            else -> ret(zero(currentReturn))
        }
    }

    /** Leaves the current position with a pending exception: to the innermost handler or out of the function. */
    fun exitStmt() {
        val h = if (handlers.isEmpty()) null else handlers[handlers.size - 1]
        if (h != null) {
            releases(scopes.size - 1, h.scopeIndex, null, true)
            br(h.label)
        } else {
            releases(scopes.size - 1, 0, null, true)
            returnDefault()
        }
    }

    /** Tests `mk_pending_exception` after a call that can throw; the exit path is a cold block. */
    fun checkPending() {
        val p = loadPending()
        val c = icmp(IrCmp.INE, p, b.constNull())
        val exitL = newLabel("exc")
        val contL = newLabel("cont")
        condBr(c, exitL, contL, true)
        startBlock(exitL)
        exitStmt()
        startBlock(contL)
    }

    fun tryStmt(s: MirStmt.Try) {
        val id = tryCounter++
        val label = b.newBlock("mkcatch_$id")
        val end = b.newBlock("mkend_$id")
        handlers.add(Handler(label, scopes.size))
        scope(s.body)
        handlers.removeAt(handlers.size - 1)
        br(end)
        startBlock(label)
        val ex = rt("mk_catch", TString, listOf(), listOf())
        caught.add(ex)
        for (c in s.catches) {
            val thenL = newLabel("catch")
            val nextL = newLabel("nomatch")
            val isInst = rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(ex, typeInfoOf(c.type)))
            condBr(isInst, thenL, nextL)
            startBlock(thenL)
            scope(c.body)
            br(end)
            startBlock(nextL)
        }
        voidCall("mk_rethrow", ex)
        exitStmt()
        caught.removeAt(caught.size - 1)
        startBlock(end)
    }

    fun loopIndex(l: MirLabel): Int {
        for (k in scopes.indices.reversed()) if (scopes[k].loop === l) return k
        throw EmitError("jump to unknown loop ${l.id}")
    }

    fun returnStmt(s: MirStmt.Return) {
        val v = s.value
        if (v == null || v === MirExpr.UnitValue) {
            releases(scopes.size - 1, 0, null, false)
            retVoid()
            return
        }
        if (v.type === TUnit) {
            expr(v, true)
            releases(scopes.size - 1, 0, null, false)
            retVoid()
            return
        }
        if (!rcT(currentReturn)) {
            // the value first: a call in it may hand a local over to the callee (RcOpt takes), which then must not be released
            val r = expr(v, true)
            releaseAll(releaseList(scopes.size - 1, 0, null), false)
            ret(r)
            return
        }
        if (rcPlan?.returnsBorrowed(curFn?.cName ?: "") == true) {
            // borrowed result (Conventions): no count for the caller
            val r = expr(v, true)
            releases(scopes.size - 1, 0, null, false)
            ret(r)
            return
        }
        val moved = fplan?.moves?.get(s)
            ?: (v as? MirExpr.Local)?.local?.takeIf { l -> scopes.any { sc -> sc.locals.any { it === l } } }
        val r = expr(v, true)
        if (moved == null && !ownedE(v) && !isStaticValue(v)) retain(r)
        releases(scopes.size - 1, 0, moved, false)
        ret(r)
    }

    fun brk(l: MirLabel): String = "brk_${l.id}"
    fun cont(l: MirLabel): String = "cont_${l.id}"

    fun whileLoop(s: MirStmt.While) {
        val hdr = newLabel("while")
        val bodyL = newLabel("wbody")
        startBlock(hdr)
        val c = expr(s.cond)
        condBr(c, bodyL, namedBlock(brk(s.label)))
        startBlock(bodyL)
        scope(s.body, loop = s.label, mid = {
            if (cont(s.label) in usedLabels) startBlock(namedBlock(cont(s.label)))
        })
        br(hdr)
        startBlock(namedBlock(brk(s.label)))
    }

    fun infiniteLoop(s: MirStmt.Loop) {
        val hdr = newLabel("loop")
        startBlock(hdr)
        scope(s.body, loop = s.label, mid = {
            if (cont(s.label) in usedLabels) startBlock(namedBlock(cont(s.label)))
        })
        br(hdr)
        startBlock(namedBlock(brk(s.label)))
    }

    fun doWhile(s: MirStmt.DoWhile) {
        val hdr = newLabel("dowhile")
        startBlock(hdr)
        scope(s.body, loop = s.label, extra = s.condStmts, epilogue = false, mid = {
            if (cont(s.label) in usedLabels) startBlock(namedBlock(cont(s.label)))
            for (c in s.condStmts) stmt(c)
            val c = expr(s.cond)
            releases(scopes.size - 1, scopes.size - 1, null, false)
            condBr(c, hdr, namedBlock(brk(s.label)))
        })
        startBlock(namedBlock(brk(s.label)))
    }

    fun forRange(s: MirStmt.ForRange) {
        val t = s.variable.type
        val lty = ity(t)
        val curSlot = b.alloca(lty, "cur" + lblCounter)
        lblCounter++
        store(expr(s.start), curSlot)
        val last = expr(s.end)
        var st = b.constInt(IrType.I64, 1L)
        if (s.step != null) {
            val sv = expr(s.step)
            st = if (s.step.type === TLong) sv else cvt(IrCast.SEXT, sv, IrType.I64)
            val bad = icmp(IrCmp.SLE, st, b.constInt(IrType.I64, 0L))
            val badL = newLabel("badstep")
            val okL = newLabel("stepok")
            condBr(bad, badL, okL, true)
            startBlock(badL)
            val f = declare("mk_raise_illegal_argument", TUnit, listOf(TString))
            call(TUnit, f, listOf(TString), listOf(cstr("Step must be positive.")))
            exitStmt()
            startBlock(okL)
        }
        // widening to 64 bits keeps the sign of Int / Long and zero-extends Char (as the C conversion does)
        fun wide(v: V): V {
            if (t === TLong) return v
            return cvt(if (t === TChar) IrCast.ZEXT else IrCast.SEXT, v, IrType.I64)
        }
        val c0 = load(t, curSlot)
        val pred = if (t === TChar) {
            if (s.ascending) (if (s.inclusive) IrCmp.ULE else IrCmp.ULT) else (if (s.inclusive) IrCmp.UGE else IrCmp.UGT)
        } else {
            if (s.ascending) (if (s.inclusive) IrCmp.SLE else IrCmp.SLT) else (if (s.inclusive) IrCmp.SGE else IrCmp.SGT)
        }
        val enter = icmp(pred, c0, last)
        val bodyL = newLabel("frbody")
        val advL = newLabel("fradv")
        condBr(enter, bodyL, namedBlock(brk(s.label)))
        startBlock(bodyL)
        val cv = load(t, curSlot)
        store(cv, slot(s.variable))
        scope(s.body, loop = s.label, mid = {
            if (cont(s.label) in usedLabels) startBlock(namedBlock(cont(s.label)))
        })
        val cur = load(t, curSlot)
        val wc = wide(cur)
        val wl = wide(last)
        val dist = if (s.ascending) binop(IrOp.SUB, wl, wc) else binop(IrOp.SUB, wc, wl)
        val stop = icmp(if (s.inclusive) IrCmp.ULT else IrCmp.ULE, dist, st)
        condBr(stop, namedBlock(brk(s.label)), advL)
        startBlock(advL)
        val adv = if (s.ascending) binop(IrOp.ADD, wc, st) else binop(IrOp.SUB, wc, st)
        val nv = if (t === TLong) adv else cvt(IrCast.TRUNC, adv, lty)
        store(nv, curSlot)
        br(bodyL)
        startBlock(namedBlock(brk(s.label)))
    }

    // ---- expressions ------------------------------------------------------------------

    fun isNullableRef(t: MirType): Boolean = t === TNString || (t is TObject && t.nullable) || t is TBoxed

    fun needsCheck(e: MirExpr): Boolean = when (e) {
        is MirExpr.Call -> throwing.callMayThrow(e.cName)
        // the inlined array accessors leave the function themselves on the failing path (see arrayAccess), no test on the fast path
        is MirExpr.RuntimeCall -> arrayAccessKind(e.cName) == null && ThrowAnalysis.runtimeCallThrows(e.cName)
        is MirExpr.VCall, is MirExpr.ICall -> throwing.dynamicMayThrow
        is MirExpr.New -> throwing.callMayThrow(e.ctorCName)
        is MirExpr.Unbox, is MirExpr.NotNull -> true
        is MirExpr.Cast -> e.mode == MirExpr.CastMode.Checked
        is MirExpr.Binary -> (e.op == BinaryOp.Div || e.op == BinaryOp.Rem) && (e.left.type === TInt || e.left.type === TLong) &&
            !((e.right is MirExpr.IntConst && e.right.value != 0) || (e.right is MirExpr.LongConst && e.right.value != 0L))
        else -> false
    }

    /** Emits [e] and returns its operand (`none` for Unit/Nothing). */
    fun expr(e: MirExpr, top: Boolean = false): V {
        if (!top && ownedE(e)) throw EmitError("owned temporary in operand position: ${e.type}")
        val v = exprInner(e)
        if (needsCheck(e)) checkPending()
        return v
    }

    fun exprInner(e: MirExpr): V {
        return when (e) {
            is MirExpr.IntConst -> b.constInt(IrType.I32, e.value.toLong())
            is MirExpr.LongConst -> b.constInt(IrType.I64, e.value)
            is MirExpr.DoubleConst -> b.constFloat(IrType.DOUBLE, e.value)
            is MirExpr.BooleanConst -> b.constInt(IrType.I1, if (e.value) 1L else 0L)
            is MirExpr.CharConst -> b.constInt(IrType.I16, e.code.toLong())
            is MirExpr.StringConst -> literal(e.value)
            MirExpr.UnitValue -> b.none
            is MirExpr.Local -> load(e.local.type, slot(e.local))
            is MirExpr.Binary -> binary(e)
            is MirExpr.Unary -> unary(e)
            is MirExpr.Convert -> convert(e)
            is MirExpr.Call -> {
                val f = sig[e.cName]
                if (f == null) {
                    if (!externalFunctions) throw EmitError("call of unknown function ${e.cName}")
                    val types = e.args.map { it.type }
                    declare(e.cName, e.type, types)
                    val vs = e.args.map { expr(it) }
                    call(e.type, b.global(e.cName), types, vs)
                } else {
                    if (f.params.size != e.args.size) throw EmitError("wrong argument count for ${e.cName}")
                    val mask = rcPlan?.ownedMask(e.cName)
                    if (mask == null) {
                        val vs = e.args.map { expr(it) }
                        call(f.returnType, b.global(fname(e.cName)), f.params.map { it.type }, vs)
                    } else {
                        ownedCall(fname(e.cName), f.returnType, f.params.map { it.type }, e.args, mask, 0, null)
                    }
                }
            }
            is MirExpr.RuntimeCall -> {
                val owns = NativeConventions.ownedArgs(e.cName)
                if (e.cName == "mk_free_dealloc" && !tr.enabled) b.none
                else if (arrayAccessKind(e.cName) != null) arrayAccess(e, fplan?.borrowedReads?.contains(e) == true)
                else if (isStringLengthCall(e)) stringLength(e)
                else if (isStringCharAtCall(e)) stringCharAt(e)
                else if (owns != null) {
                    declare(e.cName, e.type, e.args.map { it.type })
                    ownedCall(e.cName, e.type, e.args.map { it.type }, e.args, BooleanArray(e.args.size) { k -> ownsIndex(owns, k) }, 0, null)
                } else {
                    val vs = e.args.map { expr(it) }
                    rt(e.cName, e.type, e.args.map { it.type }, vs)
                }
            }
            is MirExpr.StringLength -> {
                val o = expr(e.operand)
                load(TInt, gep(o, layouts.headerSize))
            }
            is MirExpr.Load -> loadAt(e.type, expr(e.ptr))
            is MirExpr.PtrAdd -> {
                val p = expr(e.ptr)
                val n = expr(e.bytes)
                openBlock()
                b.gep(p, n, false, "")
            }
            is MirExpr.CCall -> cCall(e.extern, e.args)
            is MirExpr.CCallIndirect -> {
                val f = expr(e.callee)
                val vs = e.args.map { expr(it) }
                call(e.type, f, e.paramTypes, vs)
            }
            is MirExpr.FuncAddr -> b.global(fname(e.cName))
            is MirExpr.Conditional -> conditional(e)
            is MirExpr.Unreachable -> if (e.type === TUnit || e.type === TNothing) b.none else zero(e.type)
            is MirExpr.NullConst -> b.constNull()
            is MirExpr.Box -> {
                val v = expr(e.operand)
                val p = e.operand.type
                rt("mk_box_${boxName(p)}", TString, listOf(p), listOf(v))
            }
            is MirExpr.Unbox -> {
                val o = expr(e.operand)
                rt("mk_checked_unbox_${boxName(e.to)}", e.to, listOf(TString), listOf(o))
            }
            is MirExpr.Cast -> cast(e)
            is MirExpr.IsInstance -> {
                val o = expr(e.operand)
                val use = typeCheckPlan.uses[e]
                if (use == null) {
                    val ti = typeInfoOf(e.target)
                    rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(o, ti))
                } else {
                    typeCheck(o, e, use)
                }
            }
            is MirExpr.NotNull -> {
                val o = expr(e.operand)
                rt("mk_checked_not_null", TString, listOf(TString), listOf(o))
            }
            is MirExpr.FieldGet -> {
                val o = expr(e.obj)
                loadTag(e.type, gep(o, layouts.fieldOffset(e.owner, e.field)), fieldTag(e.owner, e.field))
            }
            is MirExpr.VCall -> {
                val r = expr(e.receiver)
                val vs = e.args.indices.map { expr(e.args[it]) }
                handOver(e.args, e.paramTypes, vs, rcPlan?.ownedMaskOf(e))
                val ti = load(TString, r)
                val vtp = load(TString, gep(ti, 4 * pb))
                val fp = load(TString, gep(vtp, e.slot * pb))
                call(e.type, fp, listOf<MirType>(TString) + e.paramTypes, listOf(r) + vs)
            }
            is MirExpr.ICall -> {
                val r = expr(e.receiver)
                val vs = e.args.indices.map { expr(e.args[it]) }
                handOver(e.args, e.paramTypes, vs, rcPlan?.ownedMaskOf(e))
                val tbl = rt("mk_itable", TString, listOf(TString, TString), listOf(r, tiOfClass(e.interfaceCName)))
                val fp = load(TString, gep(tbl, e.index * pb))
                call(e.type, fp, listOf<MirType>(TString) + e.paramTypes, listOf(r) + vs)
            }
            is MirExpr.New -> {
                val f = sig[e.ctorCName]
                if (f == null) {
                    if (!externalFunctions) throw EmitError("unknown constructor ${e.ctorCName}")
                    val types = e.args.map { it.type }
                    declare(e.ctorCName + "_new", e.type, types)
                    val vs = e.args.map { expr(it) }
                    call(e.type, b.global(e.ctorCName + "_new"), types, vs)
                } else {
                    val ps = f.params.drop(1)
                    val mask = rcPlan?.ownedMask(e.ctorCName)
                    val al = e.allocator
                    if (al != null && tr.enabled && curFn?.strict == true) {
                    // allocation through a chosen allocator (Strict code): `<ctor>_newa(allocator address, args...)`
                    newaUsed.add(e.ctorCName)
                    val newa = "${e.ctorCName}_newa"
                    b.declareDefined(newa, IrType.PTR, IrExt.NONE, listOf(IrType.I64) + ps.map { ity(it.type) }, listOf(IrExt.NONE) + ps.map { ext(it.type) }, IrAttrs.STD, IrLinkage.EXTERNAL)
                    val lead = expr(al)
                    if (mask == null) {
                        val vs = e.args.map { expr(it) }
                        call(TString, b.global(newa), listOf<MirType>(TLong) + ps.map { it.type }, listOf(lead) + vs)
                    } else {
                        ownedCall(newa, TString, ps.map { it.type }, e.args, mask, 1, lead)
                    }
                    } else if (mask == null) {
                        val vs = e.args.map { expr(it) }
                        call(TString, b.global("${e.ctorCName}_new"), ps.map { it.type }, vs)
                    } else {
                        ownedCall("${e.ctorCName}_new", TString, ps.map { it.type }, e.args, mask, 1, null)
                    }
                }
            }
            is MirExpr.ObjectGet -> call(TString, b.global("${e.classCName}_get"), listOf(), listOf())
            is MirExpr.CaughtException -> if (caught.isEmpty()) throw EmitError("CaughtException outside of a catch clause") else caught[caught.size - 1]
            is MirExpr.TypeInfoOf -> if (e.target == null) b.constNull() else typeInfoOf(e.target)
        }
    }

    /**
     * A call whose callee owns some parameters (RcOpt convention): all arguments are evaluated first, then the
     * references are handed over: a moved local gives its own away, anything else is retained.
     */
    fun ownedCall(callee: String, retType: MirType, ptypes: List<MirType>, args: List<MirExpr>, mask: BooleanArray, shift: Int, lead: V?): V {
        val vs = args.map { expr(it) }
        handOverShifted(args, ptypes, vs, mask, shift)
        if (lead != null) return call(retType, b.global(callee), listOf<MirType>(TLong) + ptypes, listOf(lead) + vs)
        return call(retType, b.global(callee), ptypes, vs)
    }

    /** The references an owned parameter takes over: a moved local gives its own away, anything else is retained. */
    fun handOverShifted(args: List<MirExpr>, ptypes: List<MirType>, vs: List<V>, mask: BooleanArray, shift: Int) {
        for (k in args.indices) {
            if (k + shift >= mask.size || !mask[k + shift] || !ptypes[k].isRef || isStaticValue(args[k])) continue
            val x = fplan?.takes?.get(args[k])
            if (x == null) retain(vs[k])
            else if (closedFor(curStmt, x)) dead.add(x.id)
            else store(b.constNull(), slot(x))
        }
    }

    /** Virtual / interface calls: [mask] is over `this` + parameters (Conventions). */
    fun handOver(args: List<MirExpr>, ptypes: List<MirType>, vs: List<V>, mask: BooleanArray?) {
        if (mask != null) handOverShifted(args, ptypes, vs, mask, 1)
    }

    fun ownsIndex(owns: IntArray, k: Int): Boolean {
        for (x in owns) if (x == k) return true
        return false
    }

    /** The element kind of an inlined array accessor (`mk_array_get_int`, `mk_array_set_obj`, ...), or null for any other runtime call. */
    fun arrayAccessKind(cName: String): String? {
        if (cName == "mk_array_length") return "length"
        val k = if (cName.startsWith("mk_array_get_")) cName.substring(13) else if (cName.startsWith("mk_array_set_")) cName.substring(13) else return null
        return if (k == "int" || k == "long" || k == "double" || k == "float" || k == "boolean" || k == "char" || k == "obj") k else null
    }

    private fun isStringLengthCall(e: MirExpr.RuntimeCall): Boolean =
        e.cName == "mk_string_length" && e.type === TInt && e.args.size == 1 && e.args[0].type === TString

    private fun isStringCharAtCall(e: MirExpr.RuntimeCall): Boolean =
        e.cName == "mk_string_char_at" && e.type === TChar && e.args.size == 2 &&
            e.args[0].type === TString && e.args[1].type === TInt

    /** Fast non-null string length load; null still takes the runtime path, which returns zero. */
    private fun stringLength(e: MirExpr.RuntimeCall): V {
        val string = expr(e.args[0])
        val fallback = newLabel("strlen_null")
        val fast = newLabel("strlen_fast")
        val merge = newLabel("strlen_merge")
        condBr(icmp(IrCmp.IEQ, string, b.constNull()), fallback, fast)

        startBlock(fast)
        val length = load(TInt, gep(string, layouts.headerSize))
        val fastEnd = curBlock
        br(merge)

        startBlock(fallback)
        val slowLength = rt("mk_string_length", TInt, listOf(TString), listOf(string))
        val slowEnd = curBlock
        br(merge)

        startBlock(merge)
        openBlock()
        return b.phi(IrType.I32, length, fastEnd, slowLength, slowEnd)
    }

    /**
     * Fast UTF-16 string indexing. Null and out-of-range cases call the original runtime function so its
     * panic behavior and message remain unchanged. The unsigned comparison covers negative indexes too.
     */
    private fun stringCharAt(e: MirExpr.RuntimeCall): V {
        val string = expr(e.args[0])
        val index = expr(e.args[1])
        val slow = newLabel("strchar_slow")
        val checkBounds = newLabel("strchar_bounds")
        val fast = newLabel("strchar_fast")
        val merge = newLabel("strchar_merge")
        condBr(icmp(IrCmp.IEQ, string, b.constNull()), slow, checkBounds, true)

        startBlock(checkBounds)
        val length = load(TInt, gep(string, layouts.headerSize))
        condBr(icmp(IrCmp.UGE, index, length), slow, fast, true)

        startBlock(fast)
        val charOffset = layouts.headerSize + 4
        val byteOffset = binop(IrOp.ADD, binop(IrOp.MUL, cvt(IrCast.SEXT, index, IrType.I64), c64(2)), c64(charOffset))
        val value = load(TChar, b.gep(string, byteOffset, true, ""))
        val fastEnd = curBlock
        br(merge)

        startBlock(slow)
        val slowValue = rt("mk_string_char_at", TChar, listOf(TString, TInt), listOf(string, index))
        val slowEnd = curBlock
        br(merge)

        startBlock(merge)
        openBlock()
        return b.phi(IrType.I16, value, fastEnd, slowValue, slowEnd)
    }

    /**
     * The inline form of the accessors of minirt.h: the length is loaded, an unsigned compare covers both bounds, the failing path
     * is a cold call of `mk_raise_index` that leaves the function (or goes to the handler) right away like a failed pending test would,
     * so the fast path tests nothing and loads nothing but the length and the element. `get_obj` retains the element (unless [borrowedRead]: the result defines a borrowing local, see RcFunctionPlan.borrowedReads), `set_obj` retains the new and releases the old element.
     */
    fun arrayAccess(e: MirExpr.RuntimeCall, borrowedRead: Boolean): V {
        val kind = arrayAccessKind(e.cName)!!
        val arr = expr(e.args[0])
        if (kind == "length") return loadTag(TInt, gep(arr, layouts.headerSize), "array length")
        val isGet = e.cName.startsWith("mk_array_get_")
        val idx = expr(e.args[1])
        val value = if (isGet) null else expr(e.args[2])
        val elemSize = when (kind) { "int" -> 4; "long" -> 8; "double" -> 8; "float" -> 4; "boolean" -> 1; "char" -> 2; else -> pb }
        val elemOff = (layouts.headerSize + 4 + 7) / 8 * 8
        val len = loadTag(TInt, gep(arr, layouts.headerSize), "array length")
        val bad = icmp(IrCmp.UGE, idx, len)
        val badL = newLabel("oob")
        val okL = newLabel("aok")
        condBr(bad, badL, okL, true)
        startBlock(badL)
        rt("mk_raise_index", TUnit, listOf(TInt, TInt), listOf(idx, len))
        exitStmt()
        startBlock(okL)
        openBlock()
        val off = b.bin(IrOp.ADD, b.bin(IrOp.MUL, b.cast(IrCast.SEXT, idx, IrType.I64, ""), c64(elemSize), ""), c64(elemOff), "")
        val p = b.gep(arr, off, true, "")
        var result: V = b.none
        val tag = "array " + kind
        if (isGet) {
            result = when (kind) {
                "boolean" -> b.cmp(IrCmp.INE, b.loadTagged(IrType.I8, p, tag, ""), b.constInt(IrType.I8, 0L), "")
                "int" -> b.loadTagged(IrType.I32, p, tag, "")
                "long" -> b.loadTagged(IrType.I64, p, tag, "")
                "double" -> b.loadTagged(IrType.DOUBLE, p, tag, "")
                "float" -> b.loadTagged(IrType.FLOAT, p, tag, "")
                "char" -> b.loadTagged(IrType.I16, p, tag, "")
                else -> b.loadTagged(IrType.PTR, p, tag, "")
            }
            if (kind == "obj" && !borrowedRead) retain(result)
        } else {
            when (kind) {
                "boolean" -> b.storeTagged(b.cast(IrCast.ZEXT, value!!, IrType.I8, ""), p, tag)
                "obj" -> {
                    retain(value!!)
                    val old = b.loadTagged(IrType.PTR, p, tag, "")
                    b.storeTagged(value, p, tag)
                    rel(old)
                }
                else -> b.storeTagged(value!!, p, tag)
            }
        }
        return result
    }

    fun conditional(e: MirExpr.Conditional): V {
        val thenL = newLabel("ct")
        val elseL = newLabel("cf")
        val endL = newLabel("cend")
        val c = expr(e.cond)
        condBr(c, thenL, elseL)
        startBlock(thenL)
        val tv = expr(e.thenValue)
        val tEnd = curBlock
        br(endL)
        startBlock(elseL)
        val ev = expr(e.elseValue)
        val eEnd = curBlock
        br(endL)
        startBlock(endL)
        val t = e.type
        if (t === TUnit || t === TNothing) return b.none
        openBlock()
        return b.phi(ity(t), tv, tEnd, ev, eEnd)
    }

    fun cast(e: MirExpr.Cast): V {
        val to = e.to
        val o = expr(e.operand)
        externTypeInfo(to)
        return when (e.mode) {
            MirExpr.CastMode.Unchecked -> o
            MirExpr.CastMode.Checked -> {
                // everything is an Any: only the null check is left (the erased `as E` of every generic getter)
                if (to is TObject && to.cName == ANY_CNAME) {
                    if (to.nullable) return o
                    return call(TString, b.global("mkh_nn"), listOf(TString), listOf(o))
                }
                if (isNullableRef(to)) rt("mk_checked_cast", TString, listOf(TString, TString), listOf(o, typeInfoOf(to)))
                else call(TString, b.global("mkh_cast_nn"), listOf(TString, TString), listOf(o, typeInfoOf(to)))
            }
            MirExpr.CastMode.Safe -> {
                val c = rt("mk_is_instance", TBoolean, listOf(TString, TString), listOf(o, typeInfoOf(to)))
                openBlock()
                b.select(c, o, b.constNull())
            }
        }
    }

    /** The declaration of the type info an expression of type [t] refers to (what the old text emitter's `externType` did). */
    fun externTypeInfo(t: MirType) {
        when (t) {
            TString, TNString -> typeInfoOf(t)
            is TObject -> typeInfoOf(t)
            is TBoxed -> typeInfoOf(t)
            else -> {}
        }
    }

    fun cmpPred(op: BinaryOp, t: MirType): IrCmp {
        if (t === TDouble || t === TFloat) {
            return when (op) {
                BinaryOp.Eq -> IrCmp.FOEQ
                BinaryOp.Ne -> IrCmp.FUNE
                BinaryOp.Lt -> IrCmp.FOLT
                BinaryOp.Le -> IrCmp.FOLE
                BinaryOp.Gt -> IrCmp.FOGT
                else -> IrCmp.FOGE
            }
        }
        // Char (uint16_t) and Boolean compare as unsigned, Int and Long as signed
        val unsigned = t === TChar || t === TBoolean || t is TPtr
        return when (op) {
            BinaryOp.Eq -> IrCmp.IEQ
            BinaryOp.Ne -> IrCmp.INE
            BinaryOp.Lt -> if (unsigned) IrCmp.ULT else IrCmp.SLT
            BinaryOp.Le -> if (unsigned) IrCmp.ULE else IrCmp.SLE
            BinaryOp.Gt -> if (unsigned) IrCmp.UGT else IrCmp.SGT
            else -> if (unsigned) IrCmp.UGE else IrCmp.SGE
        }
    }

    fun binary(e: MirExpr.Binary): V {
        val op = e.op
        val t = e.left.type
        if (op == BinaryOp.IdEq || op == BinaryOp.IdNe) {
            val a = expr(e.left)
            val c = expr(e.right)
            return icmp(if (op == BinaryOp.IdEq) IrCmp.IEQ else IrCmp.INE, a, c)
        }
        if (op == BinaryOp.AndAnd || op == BinaryOp.OrOr) {
            val a = expr(e.left)
            val aEnd = curBlock
            val rhsL = newLabel("sc")
            val endL = newLabel("scend")
            if (op == BinaryOp.AndAnd) condBr(a, rhsL, endL) else condBr(a, endL, rhsL)
            startBlock(rhsL)
            val c = expr(e.right)
            val bEnd = curBlock
            br(endL)
            startBlock(endL)
            openBlock()
            return b.phi(IrType.I1, b.constInt(IrType.I1, if (op == BinaryOp.AndAnd) 0L else 1L), aEnd, c, bEnd)
        }
        val a = expr(e.left)
        val c = expr(e.right)
        if (t === TString) {
            return when (op) {
                BinaryOp.Eq -> rt("mk_string_equals", TBoolean, listOf(TString, TString), listOf(a, c))
                BinaryOp.Ne -> {
                    val r = rt("mk_string_equals", TBoolean, listOf(TString, TString), listOf(a, c))
                    binop(IrOp.XOR, r, b.constInt(IrType.I1, 1L))
                }
                else -> throw EmitError("operator $op on String")
            }
        }
        if (op.isComparison) {
            if (t.isRef) throw EmitError("operator $op on references")
            return icmp(cmpPred(op, t), a, c)
        }
        if (t === TBoolean) {
            return when (op) {
                BinaryOp.And -> binop(IrOp.AND, a, c)
                BinaryOp.Or -> binop(IrOp.OR, a, c)
                BinaryOp.Xor -> binop(IrOp.XOR, a, c)
                else -> throw EmitError("operator $op on Boolean")
            }
        }
        if (t === TDouble || t === TFloat) {
            return when (op) {
                BinaryOp.Add -> binop(IrOp.FADD, a, c)
                BinaryOp.Sub -> binop(IrOp.FSUB, a, c)
                BinaryOp.Mul -> binop(IrOp.FMUL, a, c)
                BinaryOp.Div -> binop(IrOp.FDIV, a, c)
                BinaryOp.Rem -> binop(IrOp.FREM, a, c)
                else -> throw EmitError("operator $op on $t")
            }
        }
        if (t !== TInt && t !== TLong) throw EmitError("operator $op on $t")
        val ty = ity(t)
        val mask = if (t === TInt) 31L else 63L
        val suffix = if (t === TInt) "int" else "long"
        return when (op) {
            BinaryOp.Add -> binop(IrOp.ADD, a, c)
            BinaryOp.Sub -> binop(IrOp.SUB, a, c)
            BinaryOp.Mul -> binop(IrOp.MUL, a, c)
            BinaryOp.Div -> call(t, b.global("mkh_div_$suffix"), listOf(t, t), listOf(a, c))
            BinaryOp.Rem -> call(t, b.global("mkh_rem_$suffix"), listOf(t, t), listOf(a, c))
            BinaryOp.Shl, BinaryOp.Shr, BinaryOp.Ushr -> {
                var cnt = binop(IrOp.AND, c, b.constInt(IrType.I32, mask))
                if (t === TLong) cnt = cvt(IrCast.ZEXT, cnt, IrType.I64)
                binop(if (op == BinaryOp.Shl) IrOp.SHL else if (op == BinaryOp.Shr) IrOp.ASHR else IrOp.LSHR, a, cnt)
            }
            BinaryOp.And -> binop(IrOp.AND, a, c)
            BinaryOp.Or -> binop(IrOp.OR, a, c)
            BinaryOp.Xor -> binop(IrOp.XOR, a, c)
            else -> throw EmitError("operator $op on $t")
        }
    }

    fun unary(e: MirExpr.Unary): V {
        val a = expr(e.operand)
        val t = e.operand.type
        return when (e.op) {
            UnaryOp.Not -> binop(IrOp.XOR, a, b.constInt(IrType.I1, 1L))
            UnaryOp.Neg -> when (t) {
                TDouble, TFloat -> {
                    openBlock()
                    b.fneg(a)
                }
                TInt, TLong -> binop(IrOp.SUB, b.constInt(ity(t), 0L), a)
                else -> throw EmitError("negation of $t")
            }
            UnaryOp.Inv -> when (t) {
                TInt, TLong -> binop(IrOp.XOR, a, b.constInt(ity(t), -1L))
                else -> throw EmitError("inversion of $t")
            }
        }
    }

    fun d2i(a: V, bits: Int): V {
        openBlock()
        return b.fptosiSat(a, bits)
    }

    /** The width of an integer-like type (Byte, Short, Char, Int, Long), 0 for any other type. */
    fun intBits(t: MirType): Int = when (t) {
        TByte -> 8
        TShort, TChar -> 16
        TInt -> 32
        TLong -> 64
        else -> 0
    }

    /** The conversion of an already evaluated operand [a] of type [from] to [to]. */
    fun convert1(a: V, from: MirType, to: MirType): V {
        if (from == to) return a
        if (to === TInt && from === TDouble) return d2i(a, 32)
        if (to === TLong && from === TDouble) return d2i(a, 64)
        if (from === TDouble && intBits(to) != 0) return convert1(d2i(a, 32), TInt, to)
        if (intBits(from) != 0 && intBits(to) != 0) {
            val fb = intBits(from)
            val tb = intBits(to)
            if (fb == tb) return a
            return if (fb < tb) cvt(if (from === TChar) IrCast.ZEXT else IrCast.SEXT, a, ity(to)) else cvt(IrCast.TRUNC, a, ity(to))
        }
        throw EmitError("conversion $from -> $to")
    }

    fun convert(e: MirExpr.Convert): V {
        val a = expr(e.operand)
        val from = e.operand.type
        val to = e.to
        if (from == to) return a
        val fb = intBits(from)
        val tb = intBits(to)
        return when {
            // C interop scalars: Byte, Short, Float and pointers
            to is TPtr && from is TPtr -> a
            to is TPtr && from === TLong -> cvt(IrCast.INTTOPTR, a, IrType.PTR)
            to === TLong && from is TPtr -> cvt(IrCast.PTRTOINT, a, IrType.I64)
            fb != 0 && tb != 0 -> {
                // between Byte, Short, Char, Int and Long: sign-extend (Char: zero-extend) or truncate
                if (fb == tb) a
                else if (fb < tb) cvt(if (from === TChar) IrCast.ZEXT else IrCast.SEXT, a, ity(to))
                else cvt(IrCast.TRUNC, a, ity(to))
            }
            to === TFloat && fb != 0 -> cvt(if (from === TChar) IrCast.UITOFP else IrCast.SITOFP, a, IrType.FLOAT)
            to === TDouble && fb != 0 -> cvt(if (from === TChar) IrCast.UITOFP else IrCast.SITOFP, a, IrType.DOUBLE)
            to === TFloat && from === TDouble -> cvt(IrCast.FPTRUNC, a, IrType.FLOAT)
            to === TDouble && from === TFloat -> cvt(IrCast.FPEXT, a, IrType.DOUBLE)
            from === TFloat && tb != 0 -> convert1(cvt(IrCast.FPEXT, a, IrType.DOUBLE), TDouble, to)
            tb != 0 && from === TDouble -> convert1(a, from, to)
            else -> throw EmitError("conversion $from -> $to")
        }
    }
}
