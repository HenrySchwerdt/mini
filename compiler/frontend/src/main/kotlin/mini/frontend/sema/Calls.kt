package mini.frontend.sema

import mini.frontend.*

/*
 * Call resolution. Candidates are collected per scope level ("tower" of K2: compiler/fir/resolve/.../calls/tower/),
 * each candidate is checked against the arguments with a constraint system, the most specific applicable candidate of
 * the first level that has one wins (compiler/fir/resolve/.../calls/ConeOverloadConflictResolver), then postponed
 * arguments (lambdas) are analysed with the parameter types fixed so far and the remaining type variables are fixed
 * (compiler/fir/resolve/.../inference/FirCallCompleter).
 */

class Arg(val expr: Expr?, val name: String?, val spread: Boolean) {
    var type: Type? = null
    var trailing = false
    val postponed: Boolean
        get() {
            val e = expr ?: return false
            return isPostponedExpr(e)
        }
}

fun isPostponedExpr(e0: Expr): Boolean {
    var e = e0
    while (true) {
        if (e is ParenExpr) e = e.inner
        else if (e is LabeledExpr) e = e.expr
        else if (e is AnnotatedExpr) e = e.expr
        else break
    }
    return e is LambdaExpr || e is AnonFunExpr || e is CallableRefExpr
}

fun unwrapPostponed(e0: Expr): Expr {
    var e = e0
    while (true) {
        if (e is ParenExpr) e = e.inner
        else if (e is LabeledExpr) e = e.expr
        else if (e is AnnotatedExpr) e = e.expr
        else break
    }
    return e
}

class Candidate(val fn: FunSym, val subst: Map<TypeParamSym, Type>, val extRecv: Type?) {
    /** for the implicit `invoke` of a function-typed value */
    var invokeType: FunType? = null
    /** the implicit receiver the member (or member extension) is dispatched on */
    var dispatchRef: RecvRef? = null
    /** the implicit receiver an extension candidate is applied to */
    var extRef: RecvRef? = null
    /** `invoke` of a variable / property: where the function value comes from */
    var invokeVar: VarSym? = null
    var invokeProp: PropSym? = null
    var invokePropRecv: RecvRef? = null
}

class Binding(val paramOfArg: IntArray, val defaultsUsed: Int, val usesVararg: Boolean)

class Attempt(
    val cand: Candidate,
    val binding: Binding,
    val cs: ConstraintSystem,
    val subst: HashMap<TypeParamSym, Type>,
    val params: List<VarSym>,
    val argParam: Array<Type?>,
    val cmp: Array<Type?>,
    val cmpRecv: Type?,
    val tps: List<TypeParamSym>,
) {
    var ok = true
}

class CallRes(val ret: Type, val callee: Sym?, val calleeText: String?, val paramTypes: List<Type?>) {
    var info: CallInfo? = null
}

sealed class RecvInfo
class ValueRecv(val type: Type, val expr: Expr?, val safe: Boolean) : RecvInfo()
class ClassRecv(val cls: ClassSym) : RecvInfo()
class PkgRecv(val name: String) : RecvInfo()

// ---- candidate collection ---------------------------------------------------------------------------------------------------

private fun hasLambda(args: List<Arg>): Boolean {
    for (a in args) if (a.postponed) return true
    return false
}

fun BodyChecker.fileTopFuns(name: String): List<List<FunSym>> {
    val out = ArrayList<List<FunSym>>()
    for (g in sema.topLevel(file, name)) {
        val l = ArrayList<FunSym>()
        for (s in g) if (s is FunSym) l.add(s)
        out.add(l)
    }
    return out
}

fun BodyChecker.ctorCandidates(c: ClassSym): List<Candidate> {
    val out = ArrayList<Candidate>()
    for (k in c.ctors) out.add(Candidate(k, HashMap(), null))
    if (c.isFunInterface && c.ctors.isEmpty()) {
        var s = c.samCtor
        if (s == null) {
            val ft = samFunType(c.thisType())
            if (ft != null) {
                s = FunSym(c.name, null, null, c.file)
                s.kind = FunKind.SYNTH
                s.typeParams.addAll(c.typeParams)
                s.params = listOf(VarSym("function", ft, false, true, -1))
                s.returnType = c.thisType()
                s.sigState = 2
                s.retState = 2
                s.start = c.start
                c.samCtor = s
            }
        }
        if (s != null) out.add(Candidate(s, HashMap(), null))
    }
    return out
}

/** extension functions named [name] from the top-level scopes, as candidate groups for a receiver of type [recv] */
fun BodyChecker.extensionGroups(name: String, recv: Type, out: ArrayList<List<Candidate>>, ref: RecvRef? = null) {
    for (g in fileTopFuns(name)) {
        val l = ArrayList<Candidate>()
        for (f in g) if (f.isExtension) {
            val c = Candidate(f, HashMap(), recv)
            c.extRef = ref
            l.add(c)
        }
        if (l.isNotEmpty()) out.add(l)
    }
}

/** members and extensions applicable to an explicit receiver value of type [t] */
fun BodyChecker.memberAndExtensionGroups(t0: Type, name: String, operator: Boolean): List<List<Candidate>> {
    val groups = ArrayList<List<Candidate>>()
    val t = t0
    if (name == "invoke") {
        val ic = invokeCandidates(t0)
        if (ic.isNotEmpty() && (ops.asFunType(t0) != null || t0 is FunType)) {
            groups.add(ic)
            return groups
        }
    }
    val mem = ArrayList<Candidate>()
    if (!ops.mayBeNull(t)) {
        for (m in members.funsNamed(t, name)) {
            if (m.fn.decl?.receiver != null) continue
            mem.add(Candidate(m.fn, m.subst, null))
        }
    }
    if (mem.isNotEmpty()) groups.add(mem)
    // inner class constructors: `outer.Inner(...)`
    if (!ops.mayBeNull(t)) {
        val inner = ArrayList<Candidate>()
        for (site in ops.closure(t)) {
            val nc = site.cls.nested[name]
            if (nc != null && nc.isInner) inner.addAll(ctorCandidates(nc))
        }
        if (inner.isNotEmpty()) groups.add(inner)
    }
    // property of function type / with invoke is handled by the caller (callExpr)
    // extension members of implicit class receivers
    val ext = ArrayList<Candidate>()
    for (r in implicitReceivers()) {
        for (m in members.funsNamed(r.type, name)) {
            if (m.fn.decl?.receiver != null) {
                val c = Candidate(m.fn, m.subst, t)
                c.dispatchRef = r.ref
                ext.add(c)
            }
        }
    }
    if (ext.isNotEmpty()) groups.add(ext)
    // local extension functions
    var s: Scope? = scope
    while (s != null) {
        if (s is LocalScope) {
            val fs = s.funs[name]
            if (fs != null) {
                val l = ArrayList<Candidate>()
                for (f in fs) {
                    sema.ensureSig(f)
                    if (f.receiver != null) l.add(Candidate(f, HashMap(), t))
                }
                if (l.isNotEmpty()) groups.add(l)
            }
        }
        s = s.parent
    }
    extensionGroups(name, t, groups)
    return groups
}

fun BodyChecker.invokeCandidates(t0: Type): List<Candidate> {
    val out = ArrayList<Candidate>()
    val t = ops.asFunType(t0) ?: t0
    if (t is FunType) {
        val c = Candidate(members.invokeFor(t), HashMap(), null)
        c.invokeType = t
        out.add(c)
        return out
    }
    for (m in members.funsNamed(t, "invoke")) out.add(Candidate(m.fn, m.subst, null))
    return out
}

/** groups of candidates for `name(args)` without an explicit receiver, innermost scope first */
fun BodyChecker.unqualifiedGroups(name: String): List<List<Candidate>> {
    val groups = ArrayList<List<Candidate>>()
    var s: Scope? = scope
    var pass = 0
    while (pass < 2) {
      s = scope
      var seenRecv = false
      while (s != null) {
        when (s) {
            is LocalScope -> if (pass == 0) {
                val fs = s.funs[name]
                if (fs != null) {
                    val l = ArrayList<Candidate>()
                    for (f in fs) {
                        sema.ensureSig(f)
                        if (f.receiver == null) l.add(Candidate(f, HashMap(), null))
                    }
                    if (l.isNotEmpty()) groups.add(l)
                    // local extension functions on implicit receivers
                    for (r in implicitReceivers()) {
                        val le = ArrayList<Candidate>()
                        for (f in fs) if (f.receiver != null) {
                            val c = Candidate(f, HashMap(), r.type)
                            c.extRef = r.ref
                            le.add(c)
                        }
                        if (le.isNotEmpty()) groups.add(le)
                    }
                }
                val v = s.vars[name]
                if (v != null) {
                    val vt = v.type
                    if (vt != null) {
                        val ic = invokeCandidates(typeWithFlowVar(v, vt))
                        for (c in ic) c.invokeVar = v
                        if (ic.isNotEmpty()) groups.add(ic)
                        // `operator fun T.invoke(...)` declared as a top-level extension (C function pointers, kotlinx.cinterop)
                        val eg = ArrayList<List<Candidate>>()
                        extensionGroups("invoke", typeWithFlowVar(v, vt), eg)
                        for (g in eg) {
                            for (c in g) c.invokeVar = v
                            groups.add(g)
                        }
                    }
                }
                val lc = s.classes[name]
                if (lc != null) groups.add(ctorCandidates(lc))
            }
            is ClassScope -> if (pass == 1) {
                if (s.instance) {
                    val nrt = narrowRecv(s.cls.thisType(), s.cls.name, !seenRecv)
                    val nref = RecvRef(s.cls, null, false)
                    if (nrt !== s.cls.thisType()) nref.smart = nrt
                    addReceiverGroups(nrt, name, groups, nref)
                    seenRecv = true
                }
                val comp = s.cls.companion
                if (comp != null) addReceiverGroups(comp.thisType(), name, groups, RecvRef(comp, null, true))
                if (s.cls.isObject && !s.instance) addReceiverGroups(s.cls.thisType(), name, groups, RecvRef(s.cls, null, true))
                val nc = s.cls.nested[name]
                if (nc != null) groups.add(ctorCandidates(nc))
            }
            is ReceiverScope -> if (pass == 1) {
                val nrt2 = narrowRecv(s.type, s.label, !seenRecv)
                val nref2 = RecvRef(null, s, false)
                if (nrt2 !== s.type) nref2.smart = nrt2
                addReceiverGroups(nrt2, name, groups, nref2)
                seenRecv = true
            }
            is FileScope -> if (pass == 1) {
                for (g in sema.topLevel(file, name)) {
                    val l = ArrayList<Candidate>()
                    for (x in g) {
                        if (x is FunSym && !x.isExtension) l.add(Candidate(x, HashMap(), null))
                        else if (x is ClassSym) l.addAll(ctorCandidates(x))
                        else if (x is TypeAliasSym) {
                            val et = sema.expandAlias(x)
                            if (et is ClassType) for (k in et.cls.ctors) l.add(Candidate(k, aliasSubst(x, et), null))
                        }
                    }
                    if (l.isNotEmpty()) groups.add(l)
                    // properties with invoke
                    val il = ArrayList<Candidate>()
                    for (x in g) {
                        if (x is PropSym && x.decl?.receiver == null) {
                            sema.ensurePropSig(x)
                            val pt = sema.typeOfProp(x)
                            for (ic in invokeCandidates(pt)) {
                                ic.invokeProp = x
                                il.add(ic)
                            }
                            val eg = ArrayList<List<Candidate>>()
                            extensionGroups("invoke", pt, eg)
                            for (g in eg) for (ic in g) {
                                ic.invokeProp = x
                                il.add(ic)
                            }
                        } else if (x is ClassSym && x.isObject) {
                            for (ic in invokeCandidates(x.thisType())) {
                                ic.dispatchRef = RecvRef(x, null, true)
                                il.add(ic)
                            }
                        }
                    }
                    if (il.isNotEmpty()) groups.add(il)
                }
            }
            else -> {}
        }
        s = s.parent
      }
      pass++
    }
    return groups
}

fun BodyChecker.aliasSubst(a: TypeAliasSym, et: ClassType): Map<TypeParamSym, Type> = HashMap()

/** members of an implicit receiver, then top-level extensions applicable to it */
fun BodyChecker.addReceiverGroups(rt: Type, name: String, groups: ArrayList<List<Candidate>>, ref: RecvRef? = null) {
    val mem = ArrayList<Candidate>()
    for (m in members.funsNamed(rt, name)) {
        if (m.fn.decl?.receiver != null) continue
        val c = Candidate(m.fn, m.subst, null)
        c.dispatchRef = ref
        mem.add(c)
    }
    if (mem.isNotEmpty()) groups.add(mem)
    // member properties of function type with invoke
    val il = ArrayList<Candidate>()
    for (mp in members.propsNamed(rt, name)) {
        if (mp.prop.receiver != null) continue
        val pt = ops.substitute(sema.typeOfProp(mp.prop), mp.subst)
        for (ic in invokeCandidates(pt)) {
            ic.invokeProp = mp.prop
            ic.invokePropRecv = ref
            il.add(ic)
        }
    }
    if (il.isNotEmpty()) groups.add(il)
    extensionGroups(name, rt, groups, ref)
}

// ---- argument binding ------------------------------------------------------------------------------------------------------------

fun bindArgs(params: List<VarSym>, args: List<Arg>): Binding? {
    val assigned = BooleanArray(params.size)
    val paramOfArg = IntArray(args.size)
    var pos = 0
    var named = false
    var usesVararg = false
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.name != null) {
            named = true
            var j = 0
            var found = -1
            while (j < params.size) {
                if (params[j].name == a.name) found = j
                j++
            }
            if (found < 0) return null
            if (assigned[found] && !params[found].isVararg) return null
            assigned[found] = true
            paramOfArg[i] = found
            if (params[found].isVararg) usesVararg = true
        } else {
            var target: Int
            if (a.trailing) {
                target = params.size - 1
                if (target < 0) return null
                // the trailing lambda goes to the last parameter, skipping a vararg
                if (assigned[target] && !params[target].isVararg) return null
            } else {
                if (named && !(a.spread)) {
                    // positional after named: only allowed when it stays in place
                }
                target = pos
                if (target >= params.size) {
                    // extra positional arguments go to a trailing vararg
                    if (params.isNotEmpty() && params[params.size - 1].isVararg) target = params.size - 1 else return null
                }
            }
            if (assigned[target] && !params[target].isVararg) return null
            assigned[target] = true
            paramOfArg[i] = target
            if (params[target].isVararg) {
                usesVararg = true
            } else if (!a.trailing) {
                pos = target + 1
            }
        }
        i++
    }
    var defaults = 0
    var j = 0
    while (j < params.size) {
        if (!assigned[j]) {
            if (params[j].hasDefault) defaults++ else if (!params[j].isVararg) return null
        }
        j++
    }
    return Binding(paramOfArg, defaults, usesVararg)
}

// ---- attempting one candidate ----------------------------------------------------------------------------------------------------

fun BodyChecker.typeParamsOf(fn: FunSym): List<TypeParamSym> {
    if (fn.kind == FunKind.CONSTRUCTOR) return fn.owner!!.typeParams
    return fn.typeParams
}

fun BodyChecker.paramsOf(c: Candidate): List<VarSym> {
    val it = c.invokeType
    if (it != null) {
        val l = ArrayList<VarSym>()
        var i = 0
        for (p in ops.allParams(it)) {
            l.add(VarSym("p" + i, p, false, true, -1))
            i++
        }
        return l
    }
    sema.ensureSig(c.fn)
    return c.fn.params
}

fun BodyChecker.shapeOk(arg: Arg, pt: Type): Boolean {
    val e = arg.expr ?: return true
    val u = unwrapPostponed(e)
    if (u is LambdaExpr || u is AnonFunExpr) {
        if (pt is VarType) return true
        if (pt is FunType) {
            if (u is LambdaExpr && u.hasArrow) {
                val n = u.params.size
                return n == pt.params.size || (pt.receiver != null && n == pt.params.size + 1 && false)
            }
            if (u is AnonFunExpr) return u.fn.params.size == pt.params.size
            // without an arrow: `it`, or the implicit context parameters of a context function type
            return true
        }
        if (pt is ClassType) {
            if (pt.cls === sema.anyType.cls) return true
            if (pt.cls.isFunInterface) return true
            return false
        }
        return pt is TypeParamType
    }
    if (u is CallableRefExpr && u.name != "class" && u.receiver == null && pt is FunType) {
        // `f(::g)` with overloads of `f` over different function arities: the reference must have the arity of the parameter type
        val cands = ArrayList<RefCand>()
        for (g in unqualifiedGroups(u.name)) for (c in g) funRefCand(c, false, cands)
        if (cands.isEmpty()) return true
        val want = pt.params.size + (if (pt.receiver != null) 1 else 0)
        for (c in cands) if (c.params.size == want) return true
        return false
    }
    return true
}

fun BodyChecker.tryCandidate(c: Candidate, args: List<Arg>, typeArgs: List<Type>?): Attempt? {
    val fn = c.fn
    val params = paramsOf(c)
    val binding = bindArgs(params, args) ?: return null
    val tps = typeParamsOf(fn)
    val subst = HashMap<TypeParamSym, Type>()
    subst.putAll(c.subst)
    val cs = ConstraintSystem(sema)
    if (typeArgs != null && typeArgs.isNotEmpty()) {
        if (typeArgs.size != tps.size) return null
        var i = 0
        for (tp in tps) {
            subst[tp] = typeArgs[i]
            i++
        }
    } else {
        cs.addVariables(tps, subst)
    }
    val att = Attempt(c, binding, cs, subst, params, arrayOfNulls(args.size), arrayOfNulls(args.size), null, tps)
    // extension receiver
    var cmpRecv: Type? = null
    if (fn.receiver != null) {
        val er = c.extRecv ?: return null
        val rp = ops.substitute(fn.receiver!!, subst)
        if (!cs.addSub(er, rp)) return null
        cmpRecv = ops.substitute(fn.receiver!!, c.subst)
    } else if (c.extRecv != null && fn.kind != FunKind.FUNCTION_INVOKE) {
        return null
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        val p = params[binding.paramOfArg[i]]
        var pt: Type
        val pty = p.type ?: ErrType("param")
        if (p.isVararg && !a.spread) pt = ops.substitute(p.varargElement ?: pty, subst) else pt = ops.substitute(pty, subst)
        att.argParam[i] = pt
        att.cmp[i] = ops.substitute(if (p.isVararg && !a.spread) (p.varargElement ?: pty) else pty, c.subst)
        if (a.postponed) {
            if (!shapeOk(a, pt)) return null
        } else {
            val at = a.type
            if (at != null) {
                if (!cs.addSub(at, pt)) return null
            }
        }
        i++
    }
    if (!cs.consistent()) return null
    return Attempt(c, binding, cs, subst, params, att.argParam, att.cmp, cmpRecv, tps)
}

fun literalRank(t: Type): Int {
    if (t is ClassType) {
        return when (t.cls.fqName()) {
            "kotlin.Int" -> 0
            "kotlin.Long" -> 1
            "kotlin.Short" -> 2
            "kotlin.Byte" -> 3
            else -> 9
        }
    }
    return 9
}

fun BodyChecker.moreSpecific(a: Attempt, b: Attempt, args: List<Arg>): Boolean {
    val cs = ConstraintSystem(sema)
    val m = HashMap<TypeParamSym, Type>()
    cs.addVariables(b.tps, m)
    val ra = a.cmpRecv
    val rb = b.cmpRecv
    if (ra != null && rb != null) {
        if (!cs.addSub(ra, ops.substitute(rb, m))) return false
    }
    var i = 0
    while (i < args.size) {
        val ta = a.cmp[i]
        val tb = b.cmp[i]
        if (ta != null && tb != null) {
            val at = args[i].type
            if (at is IntLiteralType) {
                if (!(ops.isSubtype(ta, tb) || literalRank(ta) <= literalRank(tb)) && !cs.addSub(ta, ops.substitute(tb, m))) return false
            } else {
                if (a.cand.invokeType == null || true) {
                    if (!cs.addSub(ta, ops.substitute(tb, m))) return false
                }
            }
        }
        i++
    }
    return true
}

fun BodyChecker.chooseMostSpecific(ok: List<Attempt>, args: List<Arg>): Attempt {
    if (ok.size == 1) return ok[0]
    val maximal = ArrayList<Attempt>()
    for (a in ok) {
        var best = true
        for (b in ok) {
            if (a === b) continue
            if (!moreSpecific(a, b, args)) best = false
            else if (moreSpecific(b, a, args)) {
                // equally specific: decided by the tie breakers below
            }
        }
        if (best) maximal.add(a)
    }
    var pool: List<Attempt> = if (maximal.isEmpty()) ok else maximal
    if (pool.size > 1) {
        // non-generic over generic
        val ng = ArrayList<Attempt>()
        for (a in pool) if (a.tps.isEmpty()) ng.add(a)
        if (ng.isNotEmpty() && ng.size < pool.size) pool = ng
    }
    if (pool.size > 1) {
        val nv = ArrayList<Attempt>()
        for (a in pool) if (!a.binding.usesVararg) nv.add(a)
        if (nv.isNotEmpty() && nv.size < pool.size) pool = nv
    }
    if (pool.size > 1) {
        var min = 1000
        for (a in pool) if (a.binding.defaultsUsed < min) min = a.binding.defaultsUsed
        val fd = ArrayList<Attempt>()
        for (a in pool) if (a.binding.defaultsUsed == min) fd.add(a)
        pool = fd
    }
    if (pool.size > 1) {
        // a member of a subclass over the same signature of its supertype was already filtered; prefer non-abstract
        for (a in pool) if (!a.cand.fn.isAbstract) return a
    }
    return pool[0]
}

// ---- resolving a call against candidate groups --------------------------------------------------------------------------------------

fun BodyChecker.analyzeArgs(args: List<Arg>, groups: List<List<Candidate>>, typeArgs: List<Type>?) {
    // with exactly one binding candidate, its parameter types are the expected types of the arguments
    var single: Candidate? = null
    var count = 0
    for (g in groups) {
        var found = 0
        for (c in g) {
            val params = paramsOf(c)
            if (bindArgs(params, args) != null) {
                found++
                if (single == null) single = c
            }
        }
        if (found > 0) {
            count = found
            break
        }
    }
    var expectedOf: Array<Type?> = arrayOfNulls(args.size)
    if (count == 1 && single != null) {
        val params = paramsOf(single)
        val b = bindArgs(params, args)
        if (b != null) {
            val tps = typeParamsOf(single.fn)
            // type parameters the receiver already determines are known for the arguments
            val cs = ConstraintSystem(sema)
            val m = HashMap<TypeParamSym, Type>()
            m.putAll(single.subst)
            if (tps.isNotEmpty()) {
                cs.addVariables(tps, m)
                val fr = single.fn.receiver
                val er = single.extRecv
                if (fr != null && er != null) cs.addSub(er, ops.substitute(fr, m))
                for (v in cs.vars) if (v.lower.isNotEmpty() || v.equal.isNotEmpty()) cs.fix(v)
            }
            var i = 0
            while (i < args.size) {
                val p = params[b.paramOfArg[i]]
                val raw = if (p.isVararg && !args[i].spread) (p.varargElement ?: p.type) else p.type
                if (raw != null) {
                    val t = cs.resolveFixed(ops.substitute(raw, m))
                    // parameter types that still mention unsolved type parameters give no expectation
                    if (!cs.hasVars(t)) {
                        expectedOf[i] = t
                    } else if (t is VarType && !t.nullable) {
                        // a bare type variable: its declared upper bound is the expectation
                        for (u in t.v.upper) {
                            val ur = cs.resolveFixed(u)
                            if (!cs.hasVars(ur)) {
                                expectedOf[i] = ur
                                break
                            }
                        }
                    }
                }
                i++
            }
        }
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.expr != null && a.type == null && !a.postponed) {
            argNode = AstWalk.unparen(a.expr)
            val t = expr(a.expr, expectedOf[i])
            argNode = null
            a.type = t
        }
        i++
    }
}

fun mentionsTypeParams(t: Type, tps: List<TypeParamSym>): Boolean {
    if (tps.isEmpty()) return false
    when (t) {
        is TypeParamType -> {
            for (p in tps) if (p === t.tp) return true
            return false
        }
        is ClassType -> {
            for (a in t.args) {
                val x = a.type
                if (x != null && mentionsTypeParams(x, tps)) return true
            }
            return false
        }
        is FunType -> {
            val r = t.receiver
            if (r != null && mentionsTypeParams(r, tps)) return true
            for (p in t.params) if (mentionsTypeParams(p, tps)) return true
            return mentionsTypeParams(t.ret, tps)
        }
        is IsectType -> {
            for (p in t.parts) if (mentionsTypeParams(p, tps)) return true
            return false
        }
        else -> return false
    }
}

fun BodyChecker.resolveCallGroups(
    node: Ast,
    groups: List<List<Candidate>>,
    args: List<Arg>,
    typeArgs: List<Type>?,
    expected: Type?,
    analyzed: Boolean,
): CallRes? {
    if (!analyzed) analyzeArgs(args, groups, typeArgs)
    for (g in groups) {
        val ok = ArrayList<Attempt>()
        for (c in g) {
            val a = tryCandidate(c, args, typeArgs)
            if (a != null) ok.add(a)
        }
        if (ok.isNotEmpty()) {
            val best = chooseMostSpecific(ok, args)
            return completeCall(best, args, expected)
        }
    }
    leftoverArgs(args)
    return null
}

/** analyses postponed arguments of a call that could not be resolved, so that their bodies are still checked */
fun BodyChecker.leftoverArgs(args: List<Arg>) {
    for (a in args) {
        val e = a.expr ?: continue
        if (a.postponed && rec.get(e) == null) {
            val u = unwrapPostponed(e)
            if (u is LambdaExpr) analyzeLambda(u, null, null) else expr(e, null)
        }
    }
}

fun BodyChecker.completeCall(att: Attempt, args: List<Arg>, expected: Type?): CallRes {
    val cs = att.cs
    val fn = att.cand.fn
    lambdaLabel = fn.name
    var ret: Type
    val it = att.cand.invokeType
    if (it != null) {
        ret = it.ret
    } else if (fn.kind == FunKind.CONSTRUCTOR) {
        ret = ops.substitute(fn.owner!!.thisType(), att.subst)
    } else {
        ret = ops.substitute(sema.returnTypeOf(fn), att.subst)
    }
    if (expected != null && cs.hasVars(ret)) {
        cs.addSub(ret, expected)
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.postponed) {
            val pt = cs.resolveFixed(att.argParam[i] ?: ErrType("param"))
            val e = a.expr!!
            val u = unwrapPostponed(e)
            var expectedFun: FunType? = null
            if (pt is FunType) expectedFun = pt
            else if (pt is ClassType && pt.cls.isFunInterface) expectedFun = samFunType(pt)
            if (u is LambdaExpr) {
                if (expectedFun != null) {
                    cs.fixVarsOf(FunType(expectedFun.receiver, expectedFun.params, sema.unitType, false, false))
                    // result variables that the other arguments determine exactly (`getOrPut(k) { HashSet() }`) are known too
                    val rv = ArrayList<TypeVariable>()
                    cs.unfixedVars(cs.resolveFixed(expectedFun.ret), rv)
                    for (v in rv) if (v.equal.isNotEmpty() || v.lower.isNotEmpty()) cs.fix(v)
                    val ef = cs.resolveFixed(expectedFun) as FunType
                    val lt = analyzeLambda(u, ef, cs)
                    cs.addSub(lt, pt)
                    // the lambda is recorded with the final types later
                } else {
                    val lt = analyzeLambda(u, null, cs)
                    cs.addSub(lt, pt)
                }
            } else if (u is AnonFunExpr) {
                val lt = anonFunWith(u, if (expectedFun != null) cs.resolveFixed(expectedFun) as FunType else null)
                cs.addSub(lt, pt)
            } else if (u is CallableRefExpr) {
                // parameter types nothing else determines (`staticCFunction(::callback)`) come from the referenced function
                var unknown = false
                if (expectedFun != null) {
                    val free = ArrayList<TypeVariable>()
                    for (p in expectedFun.params) cs.unfixedVars(cs.resolveFixed(p), free)
                    for (v in free) if (v.equal.isEmpty() && v.lower.isEmpty()) unknown = true
                }
                if (expectedFun != null && !unknown) cs.fixVarsOf(FunType(expectedFun.receiver, expectedFun.params, sema.unitType, false, false))
                val ef = if (expectedFun != null && !unknown) cs.resolveFixed(expectedFun) as FunType else null
                val lt = callableRefWith(u, ef, if (pt is FunType || pt is VarType) null else null)
                cs.addSub(lt, pt)
            }
        }
        i++
    }
    cs.fixAll()
    ret = cs.resolveFixed(ret)
    // final parameter types, for literal completion and lambda records
    val pts = ArrayList<Type?>()
    i = 0
    while (i < args.size) {
        val p = att.argParam[i]
        pts.add(if (p != null) cs.resolveFixed(p) else null)
        i++
    }
    // lambdas get their final function type
    i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.postponed) finalizePostponed(a, cs, pts[i])
        i++
    }
    val text: String? = if (fn.kind == FunKind.FUNCTION_INVOKE) null else null
    val res = CallRes(ret, fn, text, pts)
    val targs = ArrayList<Type>()
    for (tp in att.tps) {
        val x = att.subst[tp]
        targs.add(if (x != null) cs.resolveFixed(x) else sema.nullableAny)
    }
    val info = CallInfo(fn, targs, args, att.binding.paramOfArg)
    info.ret = ret
    info.paramTypes = pts
    info.cand = att.cand
    res.info = info
    return res
}

fun BodyChecker.samFunType(t: ClassType): FunType? {
    val c = t.cls
    // the single abstract method of a fun interface
    for ((_, l) in c.funs) {
        for (f in l) {
            if (f.isAbstract) {
                sema.ensureSig(f)
                val m = ops.substOf(t)
                val ps = ArrayList<Type>()
                for (p in f.params) ps.add(ops.substitute(p.type ?: ErrType("sam"), m))
                return FunType(null, ps, ops.substitute(sema.returnTypeOf(f), m), false, false)
            }
        }
    }
    return null
}

fun BodyChecker.finalizePostponed(a: Arg, cs: ConstraintSystem, pt: Type?) {
    val e = a.expr ?: return
    val u = unwrapPostponed(e)
    for (n in listOf<Ast>(e, u)) {
        val r = rec.get(n) ?: continue
        var t = cs.resolveFixed(r.type)
        // a lambda has the (resolved) function type it is checked against when its own type fits
        if (u is LambdaExpr && pt is FunType && !pt.nullable && t is FunType && !containsError(pt) && ops.isSubtype(t, pt)) t = pt
        r.type = t
        val dr = r.declRec
        if (dr != null && t is FunType) dr.type = t.ret
    }
}

// ---- operators ----------------------------------------------------------------------------------------------------------------------

class OpResult(val ret: Type, val callee: Sym?, val calleeText: String?, val paramTypes: List<Type?>) {
    var info: CallInfo? = null
}

/** resolves an operator function `recv.name(args)` for pre-typed arguments */
fun BodyChecker.resolveOperator(name: String, recv0: Type, argTypes: List<Type>, pos: Int, expected: Type?): OpResult? {
    var recv = recv0
    if (recv is ErrType) return null
    val args = ArrayList<Arg>()
    for (t in argTypes) {
        val a = Arg(null, null, false)
        a.type = t
        args.add(a)
    }
    val groups = memberAndExtensionGroups(recv, name, true)
    val res = resolveCallGroups(BodyDummy.node, groups, args, null, expected, true) ?: return null
    val r = OpResult(res.ret, res.callee, res.calleeText, res.paramTypes)
    r.info = res.info
    return r
}

object BodyDummy {
    val node: Ast = LiteralExpr(LitKind.NULL, "")
}
