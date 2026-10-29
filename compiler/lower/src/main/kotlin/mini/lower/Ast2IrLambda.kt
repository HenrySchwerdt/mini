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
    for (pt in ft.params) {
        val vs = if (i < vars.size) vars[i] else null
        val name = if (vs != null) vs.name else "p" + i
        val p = IrValueParameter(name, irType(pt), IrParameterKind.Regular, idx++)
        p.parent = fn
        if (vs != null) varMap[vs] = p
        fn.parameters.add(p)
        i++
    }
    fn.returnType = irType(ft.ret)
    val bc = BodyCtx(ctx, fn, label, true)
    if (recvParam != null) bc.recvs.add(RecvEntry(null, null, lam, null, recvParam))
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
        val unitRet = isUnit(fn.returnType)
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
        fn.body = IrBlockBody(stmts)
    }
    return IrFunctionExpression(fn, irType(ft), "LAMBDA")
}

private fun Ast2Ir.destructureParam(names: List<DestructEntry>, p: IrValueParameter, out: ArrayList<IrStatement>) {
    var i = 1
    for (e in names) {
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

fun Ast2Ir.anonFunExpr(e: AnonFunExpr): IrExpression {
    val sym = sema.declSyms[e.fn] as? FunSym ?: fail(e, "anonymous function without symbol")
    val r = rec(e) ?: fail(e, "anonymous function without record")
    val fn = irFunction(sym)
    fn.name = "<anonymous>"
    fn.origin = "LOCAL_FUNCTION_FOR_LAMBDA"
    fn.parent = enclosingParent()
    val b = fn.body
    return IrFunctionExpression(fn, irType(r.type), "ANONYMOUS_FUNCTION")
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
    if (sym is FunSym) {
        val target: IrFunction = if (sym.kind == FunKind.CONSTRUCTOR) irConstructorOf(sym) else functionFor(sym, null)
        val call = IrFunctionReference(target, t)
        val rv = e.receiver
        for (p in target.parameters) call.arguments.add(null)
        if (rv != null && !isClassQualifier(rv)) {
            val bound = convert(rv)
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
        if (rv != null && !isClassQualifier(rv)) ref.arguments.add(convert(rv))
        else if (sym.owner != null) ref.arguments.add(implicitReceiver(RecvRef(sym.owner, null, false), e))
        return ref
    }
    fail(e, "callable reference to " + (sym?.name ?: "?"))
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
