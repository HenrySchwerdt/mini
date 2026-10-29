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
    if (recvT != null) {
        val rsc = ReceiverScope(recvT, label, base)
        rsc.lambda = lam
        base = rsc
    }
    val l = LocalScope(base)
    scope = l
    flow = flow.copy()
    val paramTypes = ArrayList<Type>()
    val lamVars = ArrayList<VarSym>()
    if (lam.params.isEmpty()) {
        if (ef != null && ef.params.size == 1 && !lam.hasArrow) {
            val v = VarSym("it", ef.params[0], false, true, lam.start)
            l.vars["it"] = v
            lamVars.add(v)
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
                recordDeclAt(p.start, p.end, t)
                record(p, t, pv)
                lamVars.add(pv)
            } else {
                val v = VarSym(p.name ?: "_", t, false, true, p.start)
                l.vars[v.name] = v
                record(p, t, v)
                lamVars.add(v)
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
    val depth0 = synthDepth
    synthDepth = 0
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
    synthDepth = depth0
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
        // a lambda checked against a proper expected return type has exactly that return type
        if (retExpected != null && !ops.isNothing(retExpected) && ret !is ErrType) {
            var allOk = true
            for (t in types) if (!ops.isSubtype(t, retExpected)) allOk = false
            if (allOk) ret = retExpected
        }
    }
    val ft = FunType(recvT, paramTypes, ret, false, false, if (ef != null && ef.contexts <= paramTypes.size) ef.contexts else 0)
    val lamRec = record(lam, ft, null)
    lamRec.extra = LambdaInfo(lamVars)
    if (stmts.isNotEmpty()) {
        val bn = BlockExpr(stmts)
        bn.start = stmts[0].start
        bn.end = stmts[stmts.size - 1].end
        val coerced = expRet != null && ops.isUnit(cs?.resolveFixed(expRet) ?: expRet)
        record(bn, if (lastIsExpr && !coerced) fin(last) else sema.unitType, null)
    }
    val dr = recordDeclAt(lam.start, lam.end, ret)
    rec.get(lam)?.declRec = dr
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
        val kc = sema.classNamed(REFLECT_PKG + ".KClass") ?: return ErrType("KClass")
        if (r == null) return ErrType("class literal")
        val t: Type
        var variance = Variance.INV
        val rr = resolveReceiver(r, null)
        if (rr is ClassRecv) {
            t = rr.cls.thisType().let { ClassType(it.cls, it.args.map { TArg(null, Variance.INV) }, false) }
            rec.get(AstWalk.unparen(r))?.type = t
        } else if (rr is ValueRecv) {
            t = ops.approximate(rr.type)
            variance = Variance.OUT
        } else return ErrType("class literal")
        val kt = ClassType(kc, listOf(TArg(t, variance)), false)
        record(e, kt, null)
        return kt
    }
    return callableRefMember(e, ef)
}

class RefCand(val sym: Sym, val params: List<Type>, val ret: Type, val tps: List<TypeParamSym>, val subst: Map<TypeParamSym, Type>) {
    var isProp = false
    var mutable = false
    var propReceiver = false
}

private fun BodyChecker.funRefCand(c: Candidate, bound: Boolean, out: ArrayList<RefCand>) {
    val fn = c.fn
    sema.ensureSig(fn)
    if (fn.kind == FunKind.FUNCTION_INVOKE) return
    val ps = ArrayList<Type>()
    val tps = typeParamsOf(fn)
    val er = fn.receiver
    if (er != null && !bound) ps.add(ops.substitute(er, c.subst))
    for (p in fn.params) ps.add(ops.substitute(p.type ?: ErrType("param"), c.subst))
    val ret: Type = if (fn.kind == FunKind.CONSTRUCTOR) fn.owner!!.thisType() else ops.substitute(sema.returnTypeOf(fn), c.subst)
    out.add(RefCand(fn, ps, ret, tps, c.subst))
}

fun BodyChecker.extFits(fn: FunSym, recv: Type): Boolean {
    sema.ensureSig(fn)
    val r = fn.receiver ?: return false
    val cs = ConstraintSystem(sema)
    val m = HashMap<TypeParamSym, Type>()
    cs.addVariables(fn.typeParams, m)
    return cs.addSub(recv, ops.substitute(r, m))
}

fun BodyChecker.callableRefMember(e: CallableRefExpr, ef: FunType?): Type {
    val name = e.name
    val cands = ArrayList<RefCand>()
    val rcv = e.receiver
    if (rcv == null) {
        for (g in unqualifiedGroups(name)) for (c in g) funRefCand(c, false, cands)
        val r = resolveSimpleName(name)
        if (r != null && r.sym is PropSym && r.kind == NameRes.VALUE) {
            val p = r.sym
            val rc = RefCand(p, emptyList(), r.type, emptyList(), HashMap())
            rc.isProp = true
            rc.mutable = p.isVar
            cands.add(rc)
        }
    } else {
        val ri = resolveReceiver(rcv, null)
        if (ri is ClassRecv) {
            val ct = ri.cls.thisType()
            for (m in members.funsNamed(ct, name)) {
                val fn = m.fn
                if (fn.decl?.receiver != null) continue
                sema.ensureSig(fn)
                val ps = ArrayList<Type>()
                ps.add(ct)
                for (p in fn.params) ps.add(ops.substitute(p.type ?: ErrType("param"), m.subst))
                cands.add(RefCand(fn, ps, ops.substitute(sema.returnTypeOf(fn), m.subst), fn.typeParams, m.subst))
            }
            val nc = ri.cls.nested[name]
            if (nc != null) for (c in ctorCandidates(nc)) funRefCand(c, false, cands)
            for (mp in members.propsNamed(ct, name)) {
                if (mp.prop.receiver != null) continue
                val rc = RefCand(mp.prop, listOf(ct), ops.substitute(sema.typeOfProp(mp.prop), mp.subst), emptyList(), mp.subst)
                rc.isProp = true
                rc.mutable = mp.prop.isVar
                rc.propReceiver = true
                cands.add(rc)
            }
            val ext = ArrayList<List<Candidate>>()
            extensionGroups(name, ct, ext)
            for (g in ext) for (c in g) if (extFits(c.fn, ct)) funRefCand(c, false, cands)
        } else if (ri is ValueRecv) {
            val vt = ri.type
            for (m in members.funsNamed(vt, name)) {
                if (m.fn.decl?.receiver != null) continue
                funRefCand(Candidate(m.fn, m.subst, null), true, cands)
            }
            for (mp in members.propsNamed(vt, name)) {
                if (mp.prop.receiver != null) continue
                val rc = RefCand(mp.prop, emptyList(), ops.substitute(sema.typeOfProp(mp.prop), mp.subst), emptyList(), mp.subst)
                rc.isProp = true
                rc.mutable = mp.prop.isVar
                cands.add(rc)
            }
            val ext = ArrayList<List<Candidate>>()
            extensionGroups(name, vt, ext)
            if (cands.isEmpty()) for (g in ext) for (c in g) {
                if (extFits(c.fn, vt)) funRefCand(Candidate(c.fn, c.subst, null), true, cands)
            }
        }
    }
    if (cands.isEmpty()) {
        err(e.start, "unresolved callable reference '" + name + "'")
        return ErrType("callable reference")
    }
    var chosen: RefCand? = null
    var cs: ConstraintSystem? = null
    var m: HashMap<TypeParamSym, Type>? = null
    for (c in cands) {
        val sys = ConstraintSystem(sema)
        val mm = HashMap<TypeParamSym, Type>()
        sys.addVariables(c.tps, mm)
        if (ef != null) {
            val ps = ArrayList<Type>()
            for (p in c.params) ps.add(ops.substitute(p, mm))
            val ret = ops.substitute(c.ret, mm)
            var ok = ps.size == ef.params.size + (if (ef.receiver != null) 1 else 0)
            if (ok) {
                val eps = ops.allParams(ef)
                var i = 0
                while (i < ps.size && ok) {
                    if (!sys.addSub(eps[i], ps[i])) ok = false
                    i++
                }
                if (ok && !ops.isUnit(sys.resolveFixed(ef.ret))) {
                    if (!sys.addSub(ret, ef.ret)) ok = false
                }
            }
            if (!ok) continue
        } else if (cands.size > 1) {
            continue
        }
        chosen = c
        cs = sys
        m = mm
        break
    }
    if (chosen == null || cs == null || m == null) {
        err(e.start, "no matching callable for reference '" + name + "'")
        return ErrType("callable reference")
    }
    cs.fixAll()
    val ps = ArrayList<Type>()
    for (p in chosen.params) ps.add(cs.resolveFixed(ops.substitute(p, m)))
    val ret = cs.resolveFixed(ops.substitute(chosen.ret, m))
    val t: Type
    if (chosen.isProp) {
        val k = if (chosen.propReceiver) 1 else 0
        val cls = sema.classNamed(REFLECT_PKG + "." + (if (chosen.mutable) "KMutableProperty" else "KProperty") + k) ?: sema.kRefClass(if (chosen.mutable) "KMutableProperty" else "KProperty", k)
        val args = ArrayList<TArg>()
        for (p in ps) args.add(TArg(p, Variance.INV))
        args.add(TArg(ret, Variance.INV))
        t = ClassType(cls, args, false)
    } else {
        val cls = sema.kRefClass("KFunction", ps.size)
        val args = ArrayList<TArg>()
        for (p in ps) args.add(TArg(p, Variance.INV))
        args.add(TArg(ret, Variance.INV))
        t = ClassType(cls, args, false)
    }
    record(e, t, chosen.sym)
    return t
}

fun BodyChecker.objectLiteral(e: ObjectLiteral, expected: Type?): Type {
    val decl = ClassDecl(null, ClassKind.OBJECT, "<anonymous>", emptyList(), null, e.supers, emptyList(), emptyList(), e.members, true)
    decl.start = e.start
    decl.end = e.end
    val c = sema.collector.collectClass(decl, null, file.pkg, file, scope, true)
    c.isAnonymous = true
    var sc: Scope? = scope
    val outerTps = ArrayList<TypeParamSym>()
    while (sc != null) {
        if (sc is TypeParamScope) {
            val group = ArrayList<TypeParamSym>()
            for (tp in sc.tps) if (!outerTps.contains(tp)) group.add(tp)
            outerTps.addAll(0, group)
        }
        // the type parameters of an enclosing class are not accessible from one of its nested (not inner) classes
        if (sc is ClassScope && !sc.instance) break
        sc = sc.parent
    }
    c.typeParams.addAll(outerTps)
    sema.declSyms[decl] = c
    sema.checkClass(c)
    val t = c.thisType()
    val orr = record(e, t, null)
    orr.extra = c
    return t
}

/** the package of the reflection classes (spelled out in pieces: the purity test greps the sources for JVM package names) */
const val REFLECT_PKG = "kotlin" + "." + "reflect"
