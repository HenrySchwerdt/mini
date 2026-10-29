package mini.frontend.sema

import mini.frontend.*

/** Lambdas, anonymous functions, callable references and object literals. */

fun BodyChecker.lambdaStandalone(e: LambdaExpr, expected: Type?): Type {
    var ef: FunType? = null
    if (expected is FunType) ef = expected
    else if (expected is ClassType && expected.cls.isFunInterface) ef = samFunType(expected)
    return analyzeLambda(e, ef, null)
}

fun BodyChecker.analyzeLambda(lam: LambdaExpr, ef: FunType?, cs: ConstraintSystem?): Type {
    val savedScope = scope
    val savedFlow = flow
    val label = pendingLabel ?: lambdaLabel
    pendingLabel = null
    var base: Scope = scope
    val recvT = ef?.receiver
    if (recvT != null) base = ReceiverScope(recvT, label, base)
    val l = LocalScope(base)
    scope = l
    flow = flow.copy()
    val paramTypes = ArrayList<Type>()
    if (lam.params.isEmpty()) {
        if (ef != null && ef.params.size == 1 && !lam.hasArrow) {
            val v = VarSym("it", ef.params[0], false, true, lam.start)
            l.vars["it"] = v
            paramTypes.add(ef.params[0])
        } else if (ef != null && !lam.hasArrow) {
            for (p in ef.params) paramTypes.add(p)
        } else if (ef != null) {
            for (p in ef.params) paramTypes.add(p)
        }
    } else {
        var i = 0
        for (p in lam.params) {
            val given = if (ef != null && i < ef.params.size) ef.params[i] else null
            val declared = if (p.type != null) sema.resolveType(p.type, scope) else null
            val t: Type = declared ?: given ?: ErrType("lambda parameter type")
            val names = p.names
            if (names != null) {
                val pv = VarSym("<destruct>", t, false, true, p.start)
                destructure(names, t, l, p.start)
            } else {
                val v = VarSym(p.name ?: "_", t, false, true, p.start)
                l.vars[v.name] = v
                record(p, t, v)
            }
            paramTypes.add(t)
            i++
        }
    }
    val retExpected: Type? = if (ef != null && (cs == null || !cs.hasVars(ef.ret))) ef.ret else null
    val target = ReturnTarget(label, retExpected, null, true)
    targets.add(target)
    lambdaDepth++
    // statements of the body, value of the last expression
    var last: Type = sema.unitType
    var lastIsExpr = false
    val stmts = lam.body
    var i = 0
    for (s in stmts) {
        val isLast = i == stmts.size - 1
        if (s is Expr) {
            last = expr(s, if (isLast) retExpected else null)
            if (isLast) lastIsExpr = true
            if (!isLast && ops.isNothing(last)) flow.dead = true
        } else {
            stmtDecl(s as Decl)
            if (isLast) lastIsExpr = false
        }
        i++
    }
    lambdaDepth--
    targets.removeAt(targets.size - 1)
    scope = savedScope
    flow = savedFlow
    var ret: Type
    val expRet = ef?.ret
    if (expRet != null && ops.isUnit(cs?.resolveFixed(expRet) ?: expRet)) {
        ret = sema.unitType
    } else {
        val types = ArrayList<Type>()
        if (lastIsExpr) types.add(fin(last)) else if (target.returned.isEmpty()) types.add(sema.unitType)
        types.addAll(target.returned)
        ret = ops.commonSuperType(types)
    }
    val ft = FunType(recvT, paramTypes, ret, false, false)
    record(lam, ft, null)
    return ft
}

fun BodyChecker.anonFun(e: AnonFunExpr, expected: Type?): Type {
    var ef: FunType? = null
    if (expected is FunType) ef = expected
    return anonFunWith(e, ef)
}

fun BodyChecker.anonFunWith(e: AnonFunExpr, ef: FunType?): Type {
    val d = e.fn
    val f = sema.collector.collectFun(d, null, file, scope)
    f.kind = FunKind.ANONYMOUS
    sema.ensureSig(f)
    // parameters without a declared type take the expected ones
    val sub = BodyChecker(sema, file)
    sub.scope = scope
    sub.flow = flow.copy()
    sub.capturedNames = capturedNames
    var i = 0
    for (p in f.params) {
        if (p.decl?.type == null && ef != null && i < ef.params.size) p.type = ef.params[i]
        i++
    }
    sub.checkLocalFunBody(f)
    val ps = ArrayList<Type>()
    for (p in f.params) ps.add(p.type ?: ErrType("param"))
    val ft = FunType(f.receiver, ps, sema.returnTypeOf(f), false, false)
    record(e, ft, null)
    return ft
}

fun BodyChecker.callableRef(e: CallableRefExpr, expected: Type?): Type = callableRefWith(e, expected as? FunType, null)

fun BodyChecker.callableRefWith(e: CallableRefExpr, ef: FunType?, unused: Type?): Type {
    if (e.name == "class") {
        val r = e.receiver
        val kc = sema.classNamed("kotlin.reflect.KClass") ?: return ErrType("KClass")
        if (r == null) return ErrType("class literal")
        val t: Type
        val rr = resolveReceiver(r, null)
        if (rr is ClassRecv) t = rr.cls.thisType().let { ClassType(it.cls, it.args.map { TArg(null, Variance.INV) }, false) }
        else if (rr is ValueRecv) t = rr.type
        else return ErrType("class literal")
        val kt = ClassType(kc, listOf(TArg(t, Variance.INV)), false)
        record(e, kt, null)
        return kt
    }
    return unsupported(e.start, "callable reference")
}

fun BodyChecker.objectLiteral(e: ObjectLiteral, expected: Type?): Type {
    val decl = ClassDecl(null, ClassKind.OBJECT, "<anonymous>", emptyList(), null, e.supers, emptyList(), emptyList(), e.members, true)
    decl.start = e.start
    decl.end = e.end
    val c = sema.collector.collectClass(decl, null, file.pkg, file, scope, true)
    c.isAnonymous = true
    sema.declSyms[decl] = c
    sema.checkClass(c)
    val t = c.thisType()
    record(e, t, null)
    return t
}
