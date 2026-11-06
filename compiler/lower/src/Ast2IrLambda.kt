package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/* Ast2Ir: lambdas, anonymous functions, object literals, callable references. */

fun Ast2Ir.enclosingParent(): IrDeclarationParent? {
    var c = ctx
    while (c != null) {
        val fn = c.fn
        if (fn != null) return fn
        val io = c.initOf
        if (io != null) return io
        c = c.parent
    }
    return null
}

fun Ast2Ir.lambdaExpr(lam: LambdaExpr, label: String?): IrExpression {
    val r = rec(lam) ?: fail(lam, "lambda without record")
    val ft = r.type as? FunType ?: fail(lam, "lambda without function type")
    val info = r.extra as? LambdaInfo
    val fn = IrSimpleFunction("<anonymous>")
    fn.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
    fn.visibility = "local"
    fn.modality = IrModality.FINAL
    fn.parent = enclosingParent()
    lambdaLabels[lam] = label
    var idx = 0
    val recvT = ft.receiver
    var recvParam: IrValueParameter? = null
    if (recvT != null) {
        recvParam = IrValueParameter("\$this\$" + (label ?: "<anonymous>"), irType(recvT), IrParameterKind.ExtensionReceiver, idx++)
        recvParam.parent = fn
        fn.parameters.add(recvParam)
    }
    val vars = if (info != null) info.params else emptyList()
    var i = 0
    val destructs = ArrayList<IrValueParameter>()
    val ctxParams = ArrayList<IrValueParameter>()
    for (pt in ft.params) {
        val vs = if (i < vars.size) vars[i] else null
        val name = if (vs != null) (if (vs.name == "_") "<unused var>" else vs.name) else "p" + i
        val isCtx = i < ft.contexts
        val p = IrValueParameter(if (isCtx) contextParamName(pt) else name, irType(pt), if (isCtx) IrParameterKind.Context else IrParameterKind.Regular, idx++)
        if (isCtx) ctxParams.add(p)
        p.parent = fn
        if (i < lam.params.size) p.startOffset = lam.params[i].start
        if (vs != null) varMap[vs] = p
        fn.parameters.add(p)
        i++
    }
    fn.returnType = irType(ft.ret)
    if (ft.suspend) makeSuspend(fn, fn.returnType)
    val bc = BodyCtx(ctx, fn, label, true)
    bc.shareTmp = lambdaInline
    lambdaInline = false
    if (recvParam != null) bc.recvs.add(RecvEntry(null, null, lam, null, recvParam))
    bc.contexts.addAll(ctxParams)
    withCtx(bc) {
        val stmts = ArrayList<IrStatement>()
        // destructured parameters
        var k = 0
        for (lp in lam.params) {
            val names = lp.names
            if (names != null) {
                val pv = fn.parameters[(if (recvParam != null) 1 else 0) + k]
                val tmpv = IrVariable("<destruct>", pv.type, false)
                destructureParam(names, pv, stmts)
            }
            k++
        }
        val body = lam.body
        val unitRet = isUnit(fn.origReturnType ?: fn.returnType)
        var j = 0
        for (s in body) {
            val last = j == body.size - 1
            if (s is Expr) {
                var ir = convert(s)
                if (last && !unitRet && !isNothing(ir.type)) ir = IrReturn(fn, ir, sema.nothingType)
                else if (last && !unitRet) { /* a Nothing-typed last expression stays */ }
                else ir = coerceToUnit(ir)
                stmts.add(ir)
            } else {
                declStatement(s as Decl, stmts)
            }
            j++
        }
        val lb = IrBlockBody(stmts)
        if (fn.isSuspend && bc.shareTmp) fn.suspendInlineTemplate = snapshotBody(fn, lb)
        fn.body = if (fn.isSuspend) lowerSuspendBody(fn, lb) else lb
    }
    fn.startOffset = lam.start
    val fe = IrFunctionExpression(fn, irType(ft), "LAMBDA")
    fe.startOffset = lam.start
    return fe
}

private fun Ast2Ir.destructureParam(names: List<DestructEntry>, p: IrValueParameter, out: ArrayList<IrStatement>) {
    var i = 1
    for (e in names) {
        if (e.name == "_") {
            i++
            continue
        }
        val r = rec(e) ?: fail(e, "destructured parameter without record")
        val vs = r.callee as? VarSym ?: fail(e, "destructured parameter without symbol")
        val info = r.call ?: fail(e, "no component" + i)
        val t = irType(vs.type ?: sema.nullableAny)
        val call = callOperator(info, getValue(p), emptyList(), t, "COMPONENT_N(index=" + i + ")")
        val iv = IrVariable(e.name, t, e.isVar)
        iv.parent = ctx?.fn
        iv.sym = vs
        iv.initializer = call
        varMap[vs] = iv
        out.add(iv)
        i++
    }
}

fun Ast2Ir.anonFunExpr(e: AnonFunExpr, label: String? = null): IrExpression {
    val sym = sema.declSyms[e.fn] as? FunSym ?: fail(e, "anonymous function without symbol")
    if (label != null) anonFunLabels[sym] = label
    val r = rec(e) ?: fail(e, "anonymous function without record")
    val fn = irFunction(sym)
    fn.name = "<anonymous>"
    fn.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
    fn.parent = enclosingParent()
    val b = fn.body
    val afe = IrFunctionExpression(fn, irType(r.type), "ANONYMOUS_FUNCTION")
    afe.startOffset = e.start
    return afe
}

fun Ast2Ir.objectLiteral(e: ObjectLiteral): IrExpression {
    val r = rec(e) ?: fail(e, "object literal without record")
    val cs = r.extra as? ClassSym ?: fail(e, "object literal without class")
    val c = irLocalClass(cs)
    val k = c.constructors.firstOrNull() ?: fail(e, "anonymous object without constructor")
    val t = irType(r.type)
    val call = IrConstructorCall(k, t)
    val stmts = ArrayList<IrStatement>()
    stmts.add(c)
    stmts.add(call)
    return IrBlock(stmts, t, "OBJECT_LITERAL")
}

// ---- callable references ---------------------------------------------------------------------------------------------------

fun Ast2Ir.callableRef(e: CallableRefExpr): IrExpression {
    val r = rec(e) ?: fail(e, "callable reference without record")
    val t = irType(r.type)
    if (e.name == "class") {
        val rv = e.receiver ?: fail(e, "class literal without receiver")
        val rr = rec(unparen(rv))
        val kt = t as? ClassType ?: fail(e, "class literal type")
        val arg = kt.args.firstOrNull()?.type ?: fail(e, "class literal without class")
        val isValue = rr != null && rr.target == null && rr.callee != null && (rr.callee is VarSym || rr.callee is PropSym) || unparen(rv) !is NameExpr && unparen(rv) !is DotExpr
        if (isClassQualifier(rv)) return IrClassReference(irType(arg), t)
        return IrGetClass(convert(rv), t)
    }
    val sym = r.callee
    val adapted = r.extra as? AdaptedRef
    if (sym is FunSym && adapted != null) return adaptedRef(e, r, adapted, sym)
    if (sym is FunSym && sym.kind == FunKind.SYNTH && sym.owner?.isEnum == true && (sym.name == "valueOf" || sym.name == "values")) {
        // `E::valueOf` / `E::values`: the synthesized static functions are called from a lambda
        val n = sym.params.size
        return adaptedRef(e, r, AdaptedRef(AdaptMap(IntArray(n) { it }, IntArray(n) { 1 }), 0), sym)
    }
    if (sym is FunSym) {
        val target: IrFunction = if (sym.kind == FunKind.CONSTRUCTOR) irConstructorOf(sym) else functionFor(sym, null)
        val call = IrFunctionReference(target, t)
        val rv = e.receiver
        for (p in target.parameters) call.arguments.add(null)
        val objQ = if (rv != null) objectQualifier(rv, r) else null
        if (rv == null && sym.owner != null && sym.kind != FunKind.CONSTRUCTOR && !sym.isExtension && target.parameters.any { it.kind == IrParameterKind.DispatchReceiver }) {
            // `::f` written in a class body: a reference to the member `this.f` (K2 binds the implicit receiver)
            val bound = implicitMemberReceiver(sym.owner, e)
            if (bound != null) for ((k, p) in target.parameters.withIndex()) {
                if (p.kind == IrParameterKind.DispatchReceiver) { call.arguments[k] = bound; break }
            }
        } else if (rv != null && (objQ != null || !isClassQualifier(rv))) {
            val bound = if (objQ != null) IrGetObjectValue(objQ, irType(objQ.thisReceiver?.type ?: sema.anyType)) else convert(rv)
            var k = 0
            for (p in target.parameters) {
                if (p.kind == IrParameterKind.DispatchReceiver || p.kind == IrParameterKind.ExtensionReceiver) {
                    call.arguments[k] = bound
                    break
                }
                k++
            }
        }
        return call
    }
    if (sym is PropSym) {
        val p = propertyOf(sym)
        val ref = IrPropertyReference(p, t)
        val rv = e.receiver
        val objQ = if (rv != null) objectQualifier(rv, r) else null
        if (objQ != null) ref.arguments.add(IrGetObjectValue(objQ, irType(objQ.thisReceiver?.type ?: sema.anyType)))
        else if (rv != null && !isClassQualifier(rv)) ref.arguments.add(convert(rv))
        else if (rv == null && sym.owner != null && !isClassRefTarget(e)) {
            // the implicit receiver (inherited members, an imported member of an object included)
            ref.arguments.add(implicitMemberReceiver(sym.owner, e) ?: implicitReceiver(RecvRef(sym.owner, null, false), e))
        }
        return ref
    }
    fail(e, "callable reference to " + (sym?.name ?: "?"))
}

/** the implicit receiver (`this`, an outer `this`, an object) that provides the member of [owner] (inherited members included) */
private fun Ast2Ir.implicitMemberReceiver(owner: ClassSym, at: Ast): IrExpression? {
    var c = ctx
    while (c != null) {
        for (i in c.recvs.indices.reversed()) {
            val r = c.recvs[i]
            // the receiver of a lambda with receiver / of an extension function provides the member too
            val rc = r.cls ?: (r.param.type as? ClassType)?.cls ?: continue
            if (rc === owner || ops.closure(rc.thisType()).any { it.cls === owner }) return IrGetValue(r.param, r.param.type, "IMPLICIT_ARGUMENT")
        }
        c = c.parent
    }
    if (owner.isObject) return IrGetObjectValue(irClass(owner), irType(owner.thisType()))
    return null
}

/** `::prop` of a member property written without a receiver in a class body is bound to `this`; the sema type says whether it is bound (KProperty0) or not (KProperty1). */
fun Ast2Ir.isClassRefTarget(e: CallableRefExpr): Boolean {
    val t = rec(e)?.type as? ClassType ?: return false
    return t.cls.name.endsWith("1")
}

fun Ast2Ir.isClassQualifier(e: Expr): Boolean {
    val u = unparen(e)
    val r = rec(u) ?: return false
    if (r.callee != null) return false
    if (u is NameExpr || u is DotExpr) {
        // a name that resolved to a class (or its companion), not to a value
        val t = r.type
        return r.target == null
    }
    return false
}

/** The name fir2ir gives the parameter of a context lambda: `$context-` and the simple name of the type. */
private fun Ast2Ir.contextParamName(t: Type): String {
    val fq = irType(t).classFqName ?: return "\$context"
    val i = fq.lastIndexOf('.')
    return "\$context-" + (if (i < 0) fq else fq.substring(i + 1))
}

/**
 * `::f` where `f` has default arguments or a `vararg`, or is called with fewer arguments than it has parameters (the expected
 * function type decides): the reference becomes a lambda `{ p0, p1 -> f(p0, p1) }` with the omitted arguments left to the call.
 */
private fun Ast2Ir.adaptedRef(e: CallableRefExpr, r: ExprRec, ad: AdaptedRef, sym: FunSym): IrExpression {
    val target: IrFunction = if (sym.kind == FunKind.CONSTRUCTOR) irConstructorOf(sym) else functionFor(sym, null)
    val kt = irType(r.type) as ClassType
    val n = kt.args.size - 1
    val retT = kt.args[n].type ?: fail(e, "adapted reference without result type")
    val fn = IrSimpleFunction("<anonymous>")
    fn.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
    fn.visibility = "local"
    fn.modality = IrModality.FINAL
    fn.parent = enclosingParent()
    val outer = ArrayList<IrStatement>()
    var bound: IrVariable? = null
    val rv = e.receiver
    if (rv != null && !isClassQualifier(rv)) {
        val bv = convert(rv)
        bound = IrVariable("\$recv", bv.type, false)
        bound.parent = ctx?.fn
        bound.initializer = bv
        outer.add(bound)
    }
    val ptypes = ArrayList<Type>()
    val lparams = ArrayList<IrValueParameter>()
    for (i in 0 until n) {
        val pt = kt.args[i].type ?: fail(e, "adapted reference without parameter type")
        ptypes.add(pt)
        val p = IrValueParameter("p" + i, pt, IrParameterKind.Regular, i)
        p.parent = fn
        fn.parameters.add(p)
        lparams.add(p)
    }
    fn.returnType = retT
    if (ad.suspendConv) makeSuspend(fn, retT)
    val call: IrFunctionAccessExpression = if (target is IrConstructor) IrConstructorCall(target, retT) else IrCall(target as IrSimpleFunction, retT)
    call.typeArguments = ad.typeArgs.map { irType(it) }
    var recvNext = 0
    var regular = 0
    for (p in target.parameters) {
        when (p.kind) {
            IrParameterKind.DispatchReceiver, IrParameterKind.ExtensionReceiver -> {
                if (bound != null) call.arguments.add(IrGetValue(bound, bound.type, null))
                else if (recvNext < lparams.size) {
                    val lp = lparams[recvNext++]
                    call.arguments.add(IrGetValue(lp, lp.type, null))
                } else call.arguments.add(null)
            }
            IrParameterKind.Context -> call.arguments.add(null)
            IrParameterKind.Regular -> {
                val j = regular++
                val start = ad.map.starts[j]
                val count = ad.map.counts[j]
                val vet = p.varargElementType
                if (vet != null) {
                    if (count == 0) call.arguments.add(null)
                    else {
                        val els = ArrayList<IrVarargElement>()
                        for (k in 0 until count) els.add(IrGetValue(lparams[start + k], lparams[start + k].type, null))
                        call.arguments.add(IrVararg(vet, els, p.type))
                    }
                } else if (count == 1) call.arguments.add(IrGetValue(lparams[start], lparams[start].type, null))
                else call.arguments.add(null)
            }
        }
    }
    val stmts = ArrayList<IrStatement>()
    if (isUnit(retT)) stmts.add(coerceToUnit(call)) else stmts.add(IrReturn(fn, call, sema.nothingType))
    fn.body = if (ad.suspendConv) lowerSuspendBody(fn, IrBlockBody(stmts)) else IrBlockBody(stmts)
    fn.startOffset = e.start
    val ft = FunType(null, ptypes, retT, false, ad.suspendConv)
    val fe = IrFunctionExpression(fn, ft, "LAMBDA")
    fe.startOffset = e.start
    val sid = adaptedRefIds.getOrPut(sym) { adaptedRefIds.size }
    fe.refKey = "adapted#" + sid + ":" + ad.map.starts.joinToString(",") + ":" + ad.map.counts.joinToString(",") + ":" + ad.suspendConv + ":" +
        (bound != null) + ":" + ad.typeArgs.joinToString(",") { renderType(it) } + ":" + ptypes.joinToString(",") { renderType(it) } + ">" + renderType(retT)
    if (outer.isEmpty()) return fe
    outer.add(fe)
    return IrBlock(outer, ft, null)
}

/**
 * Suspend conversion of a function *value* (`runS(lambdaVal)` where `lambdaVal: (String) -> String` and a `suspend (String) -> String` is
 * expected): a suspend lambda `{ p0 -> fn(p0) }` over the value, which is evaluated once.
 */
fun Ast2Ir.suspendConvertedValue(v: IrExpression, src: FunType, dst: FunType): IrExpression {
    val outer = ArrayList<IrStatement>()
    val tmp = IrVariable("\$fn", v.type, false)
    tmp.parent = ctx?.fn
    tmp.initializer = v
    outer.add(tmp)
    val ptypes = ops.allParams(dst)
    val retT = dst.ret
    val fn = IrSimpleFunction("<anonymous>")
    fn.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
    fn.visibility = "local"
    fn.modality = IrModality.FINAL
    fn.parent = enclosingParent()
    val lparams = ArrayList<IrValueParameter>()
    for ((i, pt) in ptypes.withIndex()) {
        val p = IrValueParameter("p" + i, irType(pt), IrParameterKind.Regular, i)
        p.parent = fn
        fn.parameters.add(p)
        lparams.add(p)
    }
    val rt = irType(retT)
    fn.returnType = rt
    makeSuspend(fn, rt)
    val call = IrCall(invokeFunction(ptypes.size), rt)
    call.origin = "INVOKE"
    call.arguments.add(getValue(tmp))
    for (lp in lparams) call.arguments.add(IrGetValue(lp, lp.type, null))
    val stmts = ArrayList<IrStatement>()
    if (isUnit(rt)) stmts.add(coerceToUnit(call)) else stmts.add(IrReturn(fn, call, sema.nothingType))
    fn.body = lowerSuspendBody(fn, IrBlockBody(stmts))
    val fe = IrFunctionExpression(fn, irType(dst), "LAMBDA")
    outer.add(fe)
    return IrBlock(outer, irType(dst), null)
}

/** `Obj::member` of an object (or companion): the object, to which the reference is bound; null for other qualifiers. */
private fun Ast2Ir.objectQualifier(e: Expr, r: ExprRec): IrClass? {
    if (r.kind != "objbound") return null
    val c = (r.target as? ClassType)?.cls ?: return null
    return irClass(c)
}
