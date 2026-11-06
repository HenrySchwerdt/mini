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
    val label = pendingLabel ?: lam.explicitLabel ?: lambdaLabel
    pendingLabel = null
    val probe = probeLambda
    probeLambda = false
    val inlinable = inlineLambdaNext
    inlineLambdaNext = false
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
    val retExpected: Type? = if (probe) null else if (ef != null && (cs == null || !cs.hasVars(ef.ret))) ef.ret else null
    // a result type variable that has only a proper upper bound (`useW(call { W(42) })`: `T <: W<Int?>`) expects that bound from the last expression
    var bodyExpected = retExpected
    if (bodyExpected == null && !probe && ef != null && cs != null) {
        val rt = cs.resolveFixed(ef.ret)
        if (rt is VarType && !rt.nullable && rt.v.fixed == null && rt.v.equal.isEmpty()) {
            for (u in rt.v.upper) {
                val ur = cs.resolveFixed(u)
                if (cs.hasVars(ur) || (ur is ClassType && ur.cls === sema.anyType.cls) || ur is ErrType) continue
                bodyExpected = ur
                break
            }
        }
    }
    val target = ReturnTarget(label, retExpected, null, true)
    target.isSuspend = ef != null && ef.suspend
    target.inlinable = inlinable && !target.isSuspend
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
            last = expr(s, if (isLast) bodyExpected else null)
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
    val ft = FunType(recvT, paramTypes, ret, false, ef != null && ef.suspend, if (ef != null && ef.contexts <= paramTypes.size) ef.contexts else 0)
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
    if (inlineLambdaNext && ef != null && !ef.suspend) {
        // an anonymous function for an inlinable parameter: suspension points in it belong to the enclosing function
        sub.targets.addAll(targets)
        sub.inlineFunNext = true
    }
    inlineLambdaNext = false
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
        val ru = AstWalk.unparen(r)
        val tpSym = if (ru is NameExpr && resolveSimpleName(ru.name) == null) sema.lookupClassifier(scope, ru.name) as? TypeParamSym else null
        if (tpSym != null) {
            // `T::class` of a (reified) type parameter
            val tt = TypeParamType(tpSym, false, false)
            record(ru, tt, null)
            val ktp = ClassType(kc, listOf(TArg(tt, Variance.INV)), false)
            record(e, ktp, null)
            return ktp
        }
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
    /** `Obj::member` of an object: bound to the object */
    var objBound = false
    var objType: Type? = null
    /** the scope group the candidate came from (a `::f` without receiver): the innermost group wins */
    var level = 0
}

internal fun BodyChecker.funRefCand(c: Candidate, bound: Boolean, out: ArrayList<RefCand>) {
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
        var gi = 0
        for (g in unqualifiedGroups(name)) {
            for (c in g) {
                val er0 = c.extRecv
                if (c.extRef != null && er0 != null && !extFits(c.fn, er0)) continue
                val before = cands.size
                funRefCand(c, c.extRef != null, cands)
                for (k in before until cands.size) cands[k].level = gi
            }
            gi++
        }
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
            var ct = ri.cls.thisType()
            if (e.typeArgs.isNotEmpty() && e.typeArgs.size == ct.args.size) {
                // `A<Int>::f`: the receiver type has the written arguments
                val targs = ArrayList<TArg>()
                for (ta in e.typeArgs) {
                    val tt = ta.type
                    targs.add(if (ta.star || tt == null) TArg(null, Variance.INV) else TArg(sema.resolveType(tt, scope), Variance.INV))
                }
                ct = ClassType(ct.cls, targs, false)
            }
            // `Obj::f` of an object (a companion) is bound to the object
            val objBound = ri.cls.isObject
            for (m in members.funsNamed(ct, name)) {
                val fn = m.fn
                if (fn.decl?.receiver != null) continue
                if (ri.cls.isEnum && fn.kind == FunKind.SYNTH && (name == "valueOf" || name == "values")) continue
                sema.ensureSig(fn)
                val ps = ArrayList<Type>()
                if (!objBound) ps.add(ct)
                for (p in fn.params) ps.add(ops.substitute(p.type ?: ErrType("param"), m.subst))
                val frc = RefCand(fn, ps, ops.substitute(sema.returnTypeOf(fn), m.subst), fn.typeParams, m.subst)
                frc.objBound = objBound
                if (objBound) frc.objType = ct
                cands.add(frc)
            }
            if (ri.cls.isEnum && (name == "valueOf" || name == "values")) {
                // the synthesized static functions of an enum class
                for (fn in members.staticFuns(ri.cls, name)) {
                    if (fn.kind != FunKind.SYNTH) continue
                    sema.ensureSig(fn)
                    val ps = ArrayList<Type>()
                    for (p in fn.params) ps.add(p.type ?: ErrType("param"))
                    cands.add(RefCand(fn, ps, sema.returnTypeOf(fn), emptyList(), HashMap()))
                }
            }
            val nc = ri.cls.nested[name]
            if (nc != null) for (c in ctorCandidates(nc)) {
                val before = cands.size
                funRefCand(c, false, cands)
                // `Outer::Inner` takes the outer instance first
                if (nc.isInner) for (k in before until cands.size) {
                    val rc0 = cands[k]
                    cands[k] = RefCand(rc0.sym, listOf<Type>(ct) + rc0.params, rc0.ret, rc0.tps, rc0.subst)
                }
            }
            for (mp in members.propsNamed(ct, name)) {
                if (mp.prop.receiver != null) continue
                val rc = RefCand(mp.prop, if (objBound) emptyList() else listOf(ct), ops.substitute(sema.typeOfProp(mp.prop), mp.subst), emptyList(), mp.subst)
                rc.isProp = true
                rc.mutable = mp.prop.isVar
                rc.propReceiver = !objBound
                rc.objBound = objBound
                if (objBound) rc.objType = ct
                cands.add(rc)
            }
            val xp = extensionProp(name, ct)
            val xs = xp?.sym
            if (xs is PropSym) {
                val rc = RefCand(xs, listOf(ct), xp.type, emptyList(), HashMap())
                rc.isProp = true
                rc.mutable = xs.isVar
                rc.propReceiver = true
                cands.add(rc)
            }
            val ext = ArrayList<List<Candidate>>()
            extensionGroups(name, ct, ext)
            for (g in ext) for (c in g) if (extFits(c.fn, ct)) funRefCand(c, false, cands)
            for (lf in localExtensionFuns(name)) if (extFits(lf, ct)) funRefCand(Candidate(lf, HashMap(), null), false, cands)
        } else if (ri is ValueRecv) {
            val vt = ri.type
            for (m in members.funsNamed(vt, name)) {
                if (m.fn.decl?.receiver != null) continue
                funRefCand(Candidate(m.fn, m.subst, null), true, cands)
            }
            // `outer::Inner`: the constructor of an inner class, bound to the outer instance
            for (site in ops.closure(vt)) {
                val inc = site.cls.nested[name]
                if (inc != null && inc.isInner) for (c in ctorCandidates(inc)) funRefCand(c, true, cands)
            }
            for (mp in members.propsNamed(vt, name)) {
                if (mp.prop.receiver != null) continue
                val rc = RefCand(mp.prop, emptyList(), ops.substitute(sema.typeOfProp(mp.prop), mp.subst), emptyList(), mp.subst)
                rc.isProp = true
                rc.mutable = mp.prop.isVar
                cands.add(rc)
            }
            if (cands.isEmpty()) {
                val xp = extensionProp(name, vt)
                val xs = xp?.sym
                if (xs is PropSym) {
                    val rc = RefCand(xs, emptyList(), xp.type, emptyList(), HashMap())
                    rc.isProp = true
                    rc.mutable = xs.isVar
                    cands.add(rc)
                }
            }
            val ext = ArrayList<List<Candidate>>()
            extensionGroups(name, vt, ext)
            if (cands.isEmpty()) for (g in ext) for (c in g) {
                if (extFits(c.fn, vt)) funRefCand(Candidate(c.fn, c.subst, null), true, cands)
            }
            if (cands.isEmpty()) for (lf in localExtensionFuns(name)) if (extFits(lf, vt)) funRefCand(Candidate(lf, HashMap(), null), true, cands)
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
        } else if (cands.any { it !== c && it.level == c.level }) {
            continue
        }
        chosen = c
        cs = sys
        m = mm
        break
    }
    var adapted: AdaptedRef? = null
    if ((chosen == null || cs == null || m == null) && ef != null) {
        for (c in cands) {
            val fn = c.sym as? FunSym ?: continue
            if (c.isProp) continue
            val off = c.params.size - fn.params.size
            if (off < 0 || off > 1) continue
            val eps = ops.allParams(ef)
            val map = adaptMapping(fn.params, off, eps.size) ?: continue
            val sys = ConstraintSystem(sema)
            val mm = HashMap<TypeParamSym, Type>()
            sys.addVariables(c.tps, mm)
            var ok = true
            var i = 0
            while (i < off && ok) {
                if (!sys.addSub(eps[i], ops.substitute(c.params[i], mm))) ok = false
                i++
            }
            var j = 0
            while (j < fn.params.size && ok) {
                val p = fn.params[j]
                val pt: Type = if (p.isVararg) ops.substitute(ops.substitute(p.varargElement ?: ErrType("vararg"), c.subst), mm) else ops.substitute(c.params[off + j], mm)
                var k = 0
                while (k < map.counts[j] && ok) {
                    if (!sys.addSub(eps[map.starts[j] + k], pt)) ok = false
                    k++
                }
                j++
            }
            if (ok && !ops.isUnit(sys.resolveFixed(ef.ret))) {
                if (!sys.addSub(ops.substitute(c.ret, mm), ef.ret)) ok = false
            }
            if (!ok) continue
            chosen = c
            cs = sys
            m = mm
            adapted = AdaptedRef(map, off)
            break
        }
    }
    if (chosen == null || cs == null || m == null) {
        err(e.start, "no matching callable for reference '" + name + "'")
        return ErrType("callable reference")
    }
    cs.fixAll()
    // suspend conversion: a reference to a non-suspend function where a suspend function type is expected becomes a suspend lambda;
    // so does a reference to a suspend function (`O(::id)`), whose closure would not take the continuation like a suspend function value
    val csym = chosen.sym
    // coercion to Unit: a reference to a function that returns something where a function type that returns Unit is expected
    if (ef != null && adapted == null && csym is FunSym && !chosen.isProp && ops.isUnit(ef.ret) && !ops.isUnit(cs.resolveFixed(ops.substitute(chosen.ret, m))) && ops.allParams(ef).size == chosen.params.size) {
        val off0 = chosen.params.size - csym.params.size
        if (off0 in 0..1) {
            val n0 = csym.params.size
            adapted = AdaptedRef(AdaptMap(IntArray(n0) { off0 + it }, IntArray(n0) { 1 }), off0)
        }
    }
    // `val f = ::suspendFun`: with no expected type the reference is a value of the suspend function type (calling it passes the continuation)
    val selfSuspend = ef == null && csym is FunSym && !chosen.isProp && csym.mods()?.has("suspend") == true &&
        chosen.params.none { unfixedType(cs.resolveFixed(ops.substitute(it, m))) } && !unfixedType(cs.resolveFixed(ops.substitute(chosen.ret, m)))
    if (selfSuspend && csym is FunSym) {
        val off0 = chosen.params.size - csym.params.size
        if (off0 in 0..1 && adapted == null) {
            val n0 = csym.params.size
            adapted = AdaptedRef(AdaptMap(IntArray(n0) { off0 + it }, IntArray(n0) { 1 }), off0)
        }
        if (adapted != null) adapted.suspendConv = true
    }
    if (ef != null && ef.suspend && csym is FunSym && !chosen.isProp) {
        if (adapted == null) {
            val off0 = chosen.params.size - csym.params.size
            if (off0 in 0..1) {
                val n0 = csym.params.size
                adapted = AdaptedRef(AdaptMap(IntArray(n0) { off0 + it }, IntArray(n0) { 1 }), off0)
            }
        }
        if (adapted != null) adapted.suspendConv = true
    }
    // coercion to Unit: a reference to a function with a result where `... -> Unit` is expected is adapted (its own class: it is not equal to the plain reference)
    if (ef != null && adapted == null && csym is FunSym && !chosen.isProp && ops.isUnit(cs.resolveFixed(ef.ret)) && !ops.isUnit(cs.resolveFixed(ops.substitute(chosen.ret, m)))) {
        val off0 = chosen.params.size - csym.params.size
        if (off0 in 0..1) {
            val n0 = csym.params.size
            adapted = AdaptedRef(AdaptMap(IntArray(n0) { off0 + it }, IntArray(n0) { 1 }), off0)
        }
    }
    val ps = ArrayList<Type>()
    if (adapted != null && ef != null) {
        for (p in ops.allParams(ef)) ps.add(cs.resolveFixed(p))
    } else {
        for (p in chosen.params) ps.add(cs.resolveFixed(ops.substitute(p, m)))
    }
    val ret = if (adapted != null && ef != null && ops.isUnit(ef.ret) && chosen.sym is FunSym) sema.unitType else cs.resolveFixed(ops.substitute(chosen.ret, m))
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
    val rr = record(e, t, chosen.sym)
    if (chosen.objBound) {
        rr.kind = "objbound"
        rr.target = chosen.objType
    }
    if (adapted != null) {
        val targs = ArrayList<Type>()
        for (tp in chosen.tps) targs.add(cs.resolveFixed(m[tp] ?: ErrType("type argument")))
        adapted.typeArgs = targs
        rr.extra = adapted
    }
    // the lowering reads the arguments of the KFunction type recorded above; the checker sees the suspend function type
    if (selfSuspend && adapted != null && !chosen.isProp && !ps.any { unfixedType(it) } && !unfixedType(ret)) return FunType(null, ps, ret, false, true)
    return t
}

/** does [t] still have an inference variable or an unresolved part? */
fun unfixedType(t: Type): Boolean {
    if (t is ErrType || t is VarType) return true
    if (t is ClassType) {
        for (a in t.args) {
            val x = a.type
            if (x != null && unfixedType(x)) return true
        }
        return false
    }
    if (t is FunType) {
        val r = t.receiver
        if (r != null && unfixedType(r)) return true
        for (p in t.params) if (unfixedType(p)) return true
        return unfixedType(t.ret)
    }
    return false
}

/** How the expected parameters of a function type map onto the parameters of the function a callable reference was adapted to. */
class AdaptMap(val starts: IntArray, val counts: IntArray)

/** A callable reference that needs an adapter: default arguments, `vararg` and a different number of parameters than the target. */
class AdaptedRef(val map: AdaptMap, val receiverCount: Int) {
    var typeArgs: List<Type> = emptyList()
    var suspendConv = false
}

/**
 * Assigns [n] expected parameters (the first [off] are receivers) to the parameters of a function: in order; a `vararg` takes the
 * ones that are left over (after the required parameters behind it); the parameters beyond the expected ones need a default or are
 * `vararg`. Null when that does not work, or when the reference is not adapted at all.
 */
fun adaptMapping(params: List<VarSym>, off: Int, n: Int): AdaptMap? {
    if (n < off) return null
    val starts = IntArray(params.size)
    val counts = IntArray(params.size)
    var i = off
    var j = 0
    while (j < params.size) {
        val p = params[j]
        starts[j] = i
        if (p.isVararg) {
            var req = 0
            var k = j + 1
            while (k < params.size) {
                if (!params[k].hasDefault && !params[k].isVararg) req++
                k++
            }
            var take = n - i - req
            if (take < 0) take = 0
            counts[j] = take
            i += take
        } else if (i < n) {
            counts[j] = 1
            i++
        } else if (!p.hasDefault) return null
        j++
    }
    if (i != n) return null
    return AdaptMap(starts, counts)
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
