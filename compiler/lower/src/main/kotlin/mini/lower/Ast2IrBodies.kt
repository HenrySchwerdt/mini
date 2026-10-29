package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/*
 * Ast2Ir: function bodies, constructors, property initialisers and accessors, synthesized members of data classes and
 * enums, statements and blocks.
 */

class LowerError(message: String) : Exception(message)

fun Ast2Ir.fail(at: Ast?, what: String): Nothing {
    throw LowerError((if (at != null) "offset " + at.start + ": " else "") + what)
}

// ---- contexts -------------------------------------------------------------------------------------------------------

inline fun <T> Ast2Ir.withCtx(c: BodyCtx, body: () -> T): T {
    val saved = ctx
    ctx = c
    try {
        return body()
    } finally {
        ctx = saved
    }
}

/** counter of temporaries (`tmp0_subject`): per outermost body */
fun Ast2Ir.nextTmp(): Int {
    var c = ctx
    while (c!!.parent != null) c = c.parent
    val n = c.tmpCounter
    c.tmpCounter = n + 1
    return n
}

fun Ast2Ir.newTemp(nameHint: String, type: Type, init: IrExpression, origin: String = "IR_TEMPORARY_VARIABLE"): IrVariable {
    val n = nextTmp()
    val v = IrVariable("tmp" + n + "_" + nameHint, type, false)
    v.origin = origin
    v.initializer = init
    v.parent = ctx?.fn
    return v
}

fun Ast2Ir.getValue(v: IrValueDeclaration, origin: String? = null): IrGetValue = IrGetValue(v, v.type, origin)

fun Ast2Ir.unitValue(): IrExpression = IrGetObjectValue(irClass(sema.unitType.cls), sema.unitType)

fun Ast2Ir.constBool(b: Boolean): IrExpression = IrConst(sema.booleanType, b)

fun Ast2Ir.constInt(i: Int): IrExpression = IrConst(sema.intType, i)

fun Ast2Ir.nullConst(): IrExpression = IrConst(sema.nullableNothing, null)

fun Ast2Ir.isUnit(t: Type): Boolean = t is ClassType && t.cls === sema.unitType.cls && !t.nullable

fun Ast2Ir.isNothing(t: Type): Boolean = t is ClassType && t.cls === sema.nothingType.cls && !t.nullable

fun Ast2Ir.coerceToUnit(e: IrExpression): IrExpression {
    if (isUnit(e.type) || isNothing(e.type)) return e
    return IrTypeOperatorCall(IrTypeOperator.IMPLICIT_COERCION_TO_UNIT, sema.unitType, e, sema.unitType)
}

/** the receiver context of a member function of [cls]: its dispatch receiver, and the outer instances of inner classes */
fun Ast2Ir.memberCtx(fn: IrFunction, c: IrClass, parent: BodyCtx?): BodyCtx {
    val bc = BodyCtx(parent, fn, null, false)
    val cs = c.sym
    var dispatch: IrValueParameter? = null
    for (p in fn.parameters) if (p.kind == IrParameterKind.DispatchReceiver) dispatch = p
    if (cs != null && dispatch != null && fn !is IrConstructor) bc.recvs.add(RecvEntry(cs, null, null, null, dispatch))
    if (fn is IrConstructor && cs != null) bc.recvs.add(RecvEntry(cs, null, null, null, c.thisReceiver!!))
    addOuterReceivers(bc, c)
    for (p in fn.parameters) {
        if (p.kind == IrParameterKind.ExtensionReceiver) {
            val sym = fn.sym
            val pr = (fn as? IrSimpleFunction)?.correspondingProperty?.sym
            bc.recvs.add(RecvEntry(null, if (pr == null) sym else null, null, pr, p))
        }
    }
    return bc
}

fun Ast2Ir.addOuterReceivers(bc: BodyCtx, c: IrClass) {
    var k = c
    while (k.isInner) {
        val outer = k.parent as? IrClass ?: break
        val os = outer.sym ?: break
        bc.recvs.add(RecvEntry(os, null, null, null, outer.thisReceiver!!))
        k = outer
    }
}

// ---- function bodies ---------------------------------------------------------------------------------------------

fun Ast2Ir.buildFunctionBody(fn: IrSimpleFunction, sym: FunSym): IrBody? {
    val d = sym.decl ?: return null
    val body = d.body ?: return null
    val owner = fn.parent
    val parentCtx = if (sym.kind == FunKind.LOCAL || sym.kind == FunKind.ANONYMOUS) ctx else null
    val bc: BodyCtx
    if (owner is IrClass && sym.kind != FunKind.LOCAL && sym.kind != FunKind.ANONYMOUS) bc = memberCtx(fn, owner, localClassCtx(owner))
    else {
        bc = BodyCtx(parentCtx, fn, sym.name, false)
        for (p in fn.parameters) if (p.kind == IrParameterKind.ExtensionReceiver) bc.recvs.add(RecvEntry(null, sym, null, null, p))
    }
    return withCtx(bc) { functionBody(fn, body, d.expressionBody) }
}

/** the context a member of a local class continues in (the function the class was declared in) */
fun Ast2Ir.localClassCtx(c: IrClass): BodyCtx? = localClassCtxs[c]

fun Ast2Ir.functionBody(fn: IrFunction, body: Expr, expressionBody: Boolean): IrBody {
    val stmts = ArrayList<IrStatement>()
    val rt = fn.returnType
    if (expressionBody) {
        val e = convertWithExpected(body, rt)
        if (isUnit(rt) && !isUnitReturnValueNeeded(body)) {
            stmts.add(IrReturn(fn, coerceUnitValue(e), sema.nothingType))
        } else {
            stmts.add(IrReturn(fn, e, sema.nothingType))
        }
    } else {
        val b = body as BlockExpr
        blockStatements(b.stmts, stmts, false)
    }
    return IrBlockBody(stmts)
}

private fun Ast2Ir.isUnitReturnValueNeeded(e: Expr): Boolean = true

private fun Ast2Ir.coerceUnitValue(e: IrExpression): IrExpression = e

/** converts [e] for a position that expects [expected] (return values, initialisers): inserts the implicit coercions */
fun Ast2Ir.convertWithExpected(e: Expr, expected: Type?): IrExpression {
    val ir = convert(e)
    return ir
}

// ---- statements -----------------------------------------------------------------------------------------------

/** the statements of a block; with [valueWanted] the last expression is the value of the block */
fun Ast2Ir.blockStatements(stmts: List<Stmt>, out: ArrayList<IrStatement>, valueWanted: Boolean) {
    var i = 0
    for (s in stmts) {
        val last = i == stmts.size - 1
        if (s is Expr) {
            var ir = convert(s)
            if (!(last && valueWanted)) ir = coerceToUnit(ir)
            out.add(ir)
        } else {
            declStatement(s as Decl, out)
        }
        i++
    }
}

fun Ast2Ir.declStatement(d: Decl, out: ArrayList<IrStatement>) {
    when (d) {
        is PropertyDecl -> out.add(localProperty(d))
        is DestructuringDecl -> out.add(localDestructuring(d))
        is FunDecl -> out.add(localFunction(d))
        is ClassDecl -> out.add(localClass(d))
        else -> fail(d, "declaration in block")
    }
}

fun Ast2Ir.localProperty(d: PropertyDecl): IrStatement {
    val v = sema.declSyms[d] as? VarSym ?: fail(d, "local property without symbol")
    val t = irType(v.type ?: sema.nullableAny)
    val iv = IrVariable(d.name, t, d.isVar)
    iv.parent = ctx?.fn
    iv.sym = v
    val dm = d.mods
    iv.isLateinit = dm != null && dm.has("lateinit")
    val init = d.initializer
    val deleg = d.delegate
    if (deleg != null) return localDelegatedProperty(d, v)
    if (init != null) iv.initializer = convertWithExpected(init, t)
    varMap[v] = iv
    return iv
}

fun Ast2Ir.localDestructuring(d: DestructuringDecl): IrStatement {
    val init = d.initializer ?: fail(d, "destructuring without initializer")
    val ie = convert(init)
    val out = ArrayList<IrStatement>()
    val tmp = IrVariable("<destruct>", ie.type, false)
    tmp.origin = "IR_TEMPORARY_VARIABLE"
    tmp.parent = ctx?.fn
    tmp.initializer = ie
    out.add(tmp)
    destructureInto(d.entries, tmp, out)
    return IrComposite(out, sema.unitType, "DESTRUCTURING_DECLARATION")
}

fun Ast2Ir.destructureInto(entries: List<DestructEntry>, tmp: IrVariable, out: ArrayList<IrStatement>) {
    var i = 1
    for (e in entries) {
        val r = rec(e) ?: fail(e, "destructuring entry without record")
        val vs = r.callee as? VarSym ?: fail(e, "destructuring entry without variable")
        val info = r.call ?: fail(e, "no component" + i + " function")
        val call = callOperator(info, getValue(tmp), emptyList(), irType(vs.type ?: sema.nullableAny), "COMPONENT_N(index=" + i + ")")
        val t = irType(vs.type ?: sema.nullableAny)
        val iv = IrVariable(e.name, t, e.isVar)
        iv.parent = ctx?.fn
        iv.sym = vs
        iv.initializer = call
        varMap[vs] = iv
        out.add(iv)
        i++
    }
}

// ---- local functions and classes --------------------------------------------------------------------------------

fun Ast2Ir.localFunction(d: FunDecl): IrStatement {
    val sym = sema.declSyms[d] as? FunSym ?: fail(d, "local function without symbol")
    val fn = irLocalFunction(sym)
    return fn
}

fun Ast2Ir.irLocalFunction(sym: FunSym): IrSimpleFunction {
    val ex = funMap[sym]
    if (ex != null) return ex as IrSimpleFunction
    val fn = irFunction(sym)
    fn.parent = enclosingParent()
    // the body is built now: it sees the variables of the enclosing function
    val body = fn.body
    return fn
}

fun Ast2Ir.localClass(d: ClassDecl): IrStatement {
    val sym = sema.declSyms[d] as? ClassSym ?: fail(d, "local class without symbol")
    return irLocalClass(sym)
}

fun Ast2Ir.irLocalClass(sym: ClassSym): IrClass {
    val ex = classMap[sym]
    if (ex != null) return ex
    val saved = pendingLocalCtx
    pendingLocalCtx = ctx
    val c = irClass(sym)
    pendingLocalCtx = saved
    c.parent = enclosingParent()
    localClassCtxs[c] = ctx
    // fake overrides for the new class and its nested classes
    var i = 0
    val n = allClasses.size
    while (i < n) {
        ensureFakeOverrides(allClasses[i])
        i++
    }
    return c
}

// ---- constructors -----------------------------------------------------------------------------------------------

fun Ast2Ir.anyConstructor(): IrConstructor {
    val any = sema.anyType.cls
    val pc = any.primaryCtor ?: fail(null, "kotlin.Any has no constructor")
    return irConstructor(pc, irClass(any))
}

fun Ast2Ir.buildConstructorBody(k: IrConstructor, sym: FunSym, c: IrClass): IrBody {
    val cs = c.sym!!
    val bc = memberCtx(k, c, localClassCtx(c))
    return withCtx(bc) {
        val stmts = ArrayList<IrStatement>()
        val unit = sema.unitType
        if (sym.isPrimary) {
            // super constructor call
            if (cs.isEnum) {
                val enumCls = sema.classNamed("kotlin.Enum")
                val ek = enumCls!!.primaryCtor!!
                val call = IrEnumConstructorCall(irConstructor(ek, irClass(enumCls)), unit)
                call.typeArguments = listOf(irType(cs.thisType()))
                stmts.add(call)
            } else {
                stmts.add(superCall(cs, c, k, sym))
            }
            stmts.add(IrInstanceInitializerCall(c, unit))
        } else {
            val sec = sym.secondary!!
            val del = sec.delegation
            if (del != null && del.isThis) {
                stmts.add(thisDelegation(cs, c, k, del))
            } else {
                stmts.add(superDelegation(cs, c, k, del))
                stmts.add(IrInstanceInitializerCall(c, unit))
            }
            val b = sec.body
            if (b != null) blockStatements(b.stmts, stmts, false)
        }
        IrBlockBody(stmts)
    }
}

/** `DELEGATING_CONSTRUCTOR_CALL` of a primary constructor: the super class constructor with the arguments of the class header */
fun Ast2Ir.superCall(cs: ClassSym, c: IrClass, k: IrConstructor, sym: FunSym?): IrExpression {
    val d = cs.decl
    var sc: SuperCall? = null
    if (d != null) for (s in d.supers) if (s is SuperCall) sc = s
    val unit = sema.unitType
    if (sc != null) {
        val r = rec(sc)
        val info = r?.call
        if (info != null) return delegatingCall(info, c, unit, sc.args.map { it.value })
    }
    // implicit super class: Any, or an interface-only list
    val sup = sema.superclassOf(cs)
    val target = if (sup != null) sup.cls else sema.anyType.cls
    val tk = target.primaryCtor ?: (if (target.ctors.isNotEmpty()) target.ctors[0] else null)
    if (tk == null) fail(null, "no super constructor for " + cs.name)
    val call = IrDelegatingConstructorCall(irConstructor(tk, irClass(target)), unit)
    fillDefaultsFor(call)
    return call
}

fun Ast2Ir.thisDelegation(cs: ClassSym, c: IrClass, k: IrConstructor, del: CtorDelegation): IrExpression {
    val r = recAt(del)
    val info = r?.call ?: fail(del, "unresolved constructor delegation")
    return delegatingCall(info, c, sema.unitType, del.args.map { it.value })
}

fun Ast2Ir.superDelegation(cs: ClassSym, c: IrClass, k: IrConstructor, del: CtorDelegation?): IrExpression {
    if (del != null) {
        val r = recAt(del)
        val info = r?.call
        if (info != null) return delegatingCall(info, c, sema.unitType, del.args.map { it.value })
    }
    val sup = sema.superclassOf(cs)
    val target = if (sup != null) sup.cls else sema.anyType.cls
    val tk = target.primaryCtor ?: (if (target.ctors.isNotEmpty()) target.ctors[0] else null)
    if (tk == null) fail(null, "no super constructor for " + cs.name)
    val call = IrDelegatingConstructorCall(irConstructor(tk, irClass(target)), sema.unitType)
    fillDefaultsFor(call)
    return call
}

fun Ast2Ir.fillDefaultsFor(call: IrFunctionAccessExpression) {
    val callee = when (call) {
        is IrDelegatingConstructorCall -> call.callee
        is IrConstructorCall -> call.callee
        is IrEnumConstructorCall -> call.callee
        else -> null
    }
    if (callee != null) for (p in callee.parameters) call.arguments.add(null)
}

/** The record of a synthetic node recorded at the keyword range of a delegation (`this(...)` / `super(...)`): found by position. */
fun Ast2Ir.recAt(d: CtorDelegation): ExprRec? {
    val r = rec(d)
    if (r != null && r.call != null) return r
    return null
}

// ---- initialisers -----------------------------------------------------------------------------------------------

fun Ast2Ir.initCtx(c: IrClass): BodyCtx {
    val bc = BodyCtx(localClassCtx(c), null, null, false)
    bc.initOf = c
    val cs = c.sym!!
    bc.recvs.add(RecvEntry(cs, null, null, null, c.thisReceiver!!))
    addOuterReceivers(bc, c)
    return bc
}

fun Ast2Ir.buildInitializerBody(m: InitDecl, c: IrClass): IrBlockBody {
    val bc = initCtx(c)
    return withCtx(bc) {
        val stmts = ArrayList<IrStatement>()
        val b = m.body
        if (b != null) blockStatements(b.stmts, stmts, false)
        IrBlockBody(stmts)
    }
}

fun Ast2Ir.buildPropertyInit(f: IrField, sym: PropSym, c: IrClass?): IrExpressionBody? {
    val d = sym.decl ?: return null
    val init = d.initializer ?: return null
    val bc: BodyCtx
    if (c != null) bc = initCtx(c) else bc = BodyCtx(null, null, null, false)
    return withCtx(bc) { IrExpressionBody(convertWithExpected(init, f.type)) }
}

fun Ast2Ir.buildCtorPropInit(f: IrField, sym: PropSym, c: IrClass): IrExpressionBody? {
    val cp = sym.ctorParam ?: return null
    val pc = c.sym!!.primaryCtor ?: return null
    val k = funMap[pc] as? IrConstructor ?: return null
    var target: IrValueParameter? = null
    for (p in k.parameters) if (p.name == cp.name && p.kind == IrParameterKind.Regular) target = p
    val t = target ?: return null
    return IrExpressionBody(IrGetValue(t, t.type, "INITIALIZE_PROPERTY_FROM_PARAMETER"))
}

fun Ast2Ir.buildEnumEntryInit(es: EnumEntrySym, c: IrClass): IrExpressionBody? {
    val cs = c.sym!!
    val bc = BodyCtx(null, null, null, false)
    return withCtx(bc) {
        val r = rec(es.decl)
        val info = r?.call ?: fail(es.decl, "enum entry without constructor call")
        val k = funMap[info.fn] as? IrConstructor ?: irConstructor(info.fn, c)
        val call = IrEnumConstructorCall(k, irType(cs.thisType()))
        val argExprs = ArrayList<Expr?>()
        val a = es.decl.args
        fillCallArgs(call, k, info, null, null, null)
        IrExpressionBody(call)
    }
}

fun Ast2Ir.buildDelegateInit(p: IrProperty, sym: PropSym, c: IrClass?, e: Expr): IrExpressionBody? {
    val bc: BodyCtx
    if (c != null) bc = initCtx(c) else bc = BodyCtx(null, null, null, false)
    return withCtx(bc) { IrExpressionBody(convert(e)) }
}

fun Ast2Ir.buildDefaultValue(fn: IrFunction, ip: IrValueParameter, e: Expr): IrExpressionBody {
    val owner = fn.parent
    val bc: BodyCtx
    if (fn is IrConstructor && owner is IrClass) bc = memberCtx(fn, owner, localClassCtx(owner))
    else if (owner is IrClass) bc = memberCtx(fn, owner, localClassCtx(owner))
    else {
        bc = BodyCtx(if (fn.sym?.kind == FunKind.LOCAL) ctx else null, fn, null, false)
        for (p in fn.parameters) if (p.kind == IrParameterKind.ExtensionReceiver) bc.recvs.add(RecvEntry(null, fn.sym, null, null, p))
    }
    return withCtx(bc) { IrExpressionBody(convertWithExpected(e, ip.type)) }
}

// ---- accessors -----------------------------------------------------------------------------------------------

fun Ast2Ir.buildAccessorBody(f: IrSimpleFunction, p: IrProperty, sym: PropSym, c: IrClass?, isGetter: Boolean): IrBody? {
    val d = sym.decl
    val acc = if (d == null) null else if (isGetter) d.getter else d.setter
    val bc: BodyCtx
    if (c != null) bc = memberCtx(f, c, localClassCtx(c)) else {
        bc = BodyCtx(null, f, null, false)
        for (q in f.parameters) if (q.kind == IrParameterKind.ExtensionReceiver) bc.recvs.add(RecvEntry(null, null, null, sym, q))
    }
    return withCtx(bc) {
        val stmts = ArrayList<IrStatement>()
        val field = p.backingField
        if (d != null && d.delegate != null) {
            stmts.add(delegatedAccessor(f, p, sym, c, isGetter))
        } else if (acc != null && acc.body != null) {
            if (isGetter) {
                val accBody = acc.body!!
                if (acc.expressionBody) {
                    stmts.add(IrReturn(f, convert(accBody), sema.nothingType))
                } else {
                    blockStatements((accBody as BlockExpr).stmts, stmts, false)
                }
            } else {
                // parameter symbol of the setter
                val pv = acc.param
                val vs = if (pv != null) (rec(pv)?.callee as? VarSym) else null
                if (vs != null) varMap[vs] = f.parameters[f.parameters.size - 1]
                val accBody2 = acc.body!!
                if (acc.expressionBody) {
                    stmts.add(coerceToUnit(convert(accBody2)))
                } else {
                    blockStatements((accBody2 as BlockExpr).stmts, stmts, false)
                }
            }
        } else {
            // default accessor: reads / writes the backing field
            val fld = field ?: return@withCtx null
            val recv = f.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
            val recvExpr = if (recv != null) getValue(recv) else null
            if (isGetter) {
                stmts.add(IrReturn(f, IrGetField(fld, recvExpr, fld.type), sema.nothingType))
            } else {
                val value = f.parameters[f.parameters.size - 1]
                stmts.add(IrSetField(fld, recvExpr, getValue(value), sema.unitType))
            }
        }
        IrBlockBody(stmts)
    }
}

/** the property reference passed to `getValue` / `setValue` (not used by the lowering) */
fun Ast2Ir.delegateReference(name: String, type: Type, arity: Int): IrExpression {
    val dummy = IrProperty(name, null)
    val cls = sema.classNamed(REFLECT_PKG + ".KProperty" + arity) ?: sema.kRefClass("KProperty", arity)
    val args = ArrayList<TArg>()
    if (arity == 1) args.add(TArg(sema.nullableAny, Variance.INV))
    args.add(TArg(type, Variance.INV))
    val t = ClassType(cls, args, false)
    val ref = IrPropertyReference(dummy, t)
    ref.origin = "PROPERTY_REFERENCE_FOR_DELEGATE"
    return ref
}

fun Ast2Ir.delegatedAccessor(f: IrSimpleFunction, p: IrProperty, sym: PropSym, c: IrClass?, isGetter: Boolean): IrStatement {
    val d = sym.decl ?: fail(null, "delegated property without declaration")
    val di = rec(d)?.extra as? DelegateInfo ?: fail(d, "delegated property without operator information")
    val fld = p.backingField ?: fail(d, "delegated property without delegate field")
    val recv = f.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
    val delegateValue = IrGetField(fld, if (recv != null) getValue(recv) else null, fld.type)
    val thisRef: IrExpression = if (recv != null) getValue(recv) else nullConst()
    val t = irType(sema.typeOfProp(sym))
    val propRef = delegateReference(sym.name, t, if (recv != null) 1 else 0)
    if (isGetter) {
        val gi = di.getValue ?: fail(d, "getValue is not resolved")
        val call = callOperator(gi, delegateValue, listOf(thisRef, propRef), t, null)
        return IrReturn(f, call, sema.nothingType)
    }
    val si = di.setValue ?: fail(d, "setValue is not resolved")
    val value = getValue(f.parameters[f.parameters.size - 1])
    return callOperator(si, delegateValue, listOf(thisRef, propRef, value), sema.unitType, null)
}

fun Ast2Ir.localDelegatedProperty(d: PropertyDecl, v: VarSym): IrStatement {
    val di = rec(d)?.extra as? DelegateInfo ?: fail(d, "delegated local property without operator information")
    val t = irType(v.type ?: sema.nullableAny)
    val prop = IrLocalDelegatedProperty(d.name)
    prop.parent = enclosingParent()
    val de = convert(d.delegate!!)
    val dv = IrVariable(d.name + "\$delegate", de.type, false)
    dv.origin = "PROPERTY_DELEGATE"
    dv.parent = ctx?.fn
    dv.initializer = de
    prop.delegate = dv
    // getter
    val g = IrSimpleFunction("<get-" + d.name + ">")
    g.origin = "DELEGATED_PROPERTY_ACCESSOR"
    g.visibility = "local"
    g.parent = enclosingParent()
    g.returnType = t
    val gc = BodyCtx(ctx, g, null, false)
    withCtx(gc) {
        val gi = di.getValue ?: fail(d, "getValue is not resolved")
        val call = callOperator(gi, getValue(dv), listOf(nullConst(), delegateReference(d.name, t, 0)), t, null)
        val st = ArrayList<IrStatement>()
        st.add(IrReturn(g, call, sema.nothingType))
        g.body = IrBlockBody(st)
    }
    prop.getter = g
    if (d.isVar) {
        val s = IrSimpleFunction("<set-" + d.name + ">")
        s.origin = "DELEGATED_PROPERTY_ACCESSOR"
        s.visibility = "local"
        s.parent = enclosingParent()
        s.returnType = sema.unitType
        val vp = IrValueParameter("value", t, IrParameterKind.Regular, 0)
        vp.parent = s
        s.parameters.add(vp)
        val sc = BodyCtx(ctx, s, null, false)
        withCtx(sc) {
            val si = di.setValue ?: fail(d, "setValue is not resolved")
            val call = callOperator(si, getValue(dv), listOf(nullConst(), delegateReference(d.name, t, 0), getValue(vp)), sema.unitType, null)
            val st = ArrayList<IrStatement>()
            st.add(call)
            s.body = IrBlockBody(st)
        }
        prop.setter = s
    }
    delegatedLocals[v] = prop
    return prop
}
