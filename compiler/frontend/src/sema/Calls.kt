package mini.frontend.sema

import mini.frontend.*

/*
 * Call resolution. Candidates are collected per scope level ("tower" of K2: compiler/fir/resolve/.../calls/tower/),
 * each candidate is checked against the arguments with a constraint system, the most specific applicable candidate of
 * the first level that has one wins (compiler/fir/resolve/.../calls/ConeOverloadConflictResolver), then postponed
 * arguments (lambdas) are analysed with the parameter types fixed so far and the remaining type variables are fixed
 * (compiler/fir/resolve/.../inference/FirCallCompleter).
 */

class Arg(val expr: Expr?, val name: String?, var spread: Boolean) {
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
    var label: String? = null
    while (true) {
        if (e is ParenExpr) e = e.inner
        else if (e is LabeledExpr) { label = e.label; e = e.expr }
        else if (e is AnnotatedExpr) e = e.expr
        else break
    }
    if (label != null && e is LambdaExpr) e.explicitLabel = label
    return e
}

/** the label written on a lambda argument (`build outer@ { ... this@outer ... }`) */
fun postponedLabel(e0: Expr): String? {
    var e = e0
    while (true) {
        if (e is ParenExpr) e = e.inner
        else if (e is LabeledExpr) return e.label
        else if (e is AnnotatedExpr) e = e.expr
        else return null
    }
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
    /** `invoke` of the value the callee name denotes (an enum entry, an object behind a type alias, an extension property) */
    var invokeCallee = false
    /** the explicit type arguments of the call were used to instantiate the candidate (a constructor behind a generic typealias) */
    var ignoreTypeArgs = false
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
    /** bounds this attempt added to postponed (builder inference) variables: taken back after the attempt, put in again when it is chosen */
    var openDelta: List<Triple<TypeVariable, Int, Type>> = emptyList()
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
            val via = sema.importedVia[f]
            val c = Candidate(f, sema.importedSubstOf(f), recv)
            c.extRef = ref
            // a member extension an object inherits, imported by name: the object is the dispatch receiver
            if (via != null) c.dispatchRef = RecvRef(via, null, true)
            l.add(c)
        }
        if (l.isNotEmpty()) out.add(l)
    }
}

/** the local extension functions named [name] in scope (`fun A.f() {}` written in a function body) */
fun BodyChecker.localExtensionFuns(name: String): List<FunSym> {
    val out = ArrayList<FunSym>()
    var s: Scope? = scope
    while (s != null) {
        if (s is LocalScope) {
            val fs = s.funs[name]
            if (fs != null) for (f in fs) {
                sema.ensureSig(f)
                if (f.receiver != null) out.add(f)
            }
        }
        s = s.parent
    }
    return out
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
        // `outer.OI(...)` where `typealias OI = Outer.Inner`
        val ac = sema.lookupClassifier(scope, name)
        if (ac is TypeAliasSym) {
            val aet = sema.expandAlias(ac)
            if (aet is ClassType && aet.cls.isInner) {
                val oc = aet.cls.outer
                if (oc != null && ops.asSuper(t, oc) != null) groups.add(ctorCandidates(aet.cls))
            }
        }
    }
    // property of function type / with invoke is handled by the caller (callExpr)
    // extension members of implicit class receivers
    implicitMemberExtGroup(name, t, groups)
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
                // a variable of the same scope this one replaced (a parameter): called when the newer one has no `invoke`
                val sh = s.shadowedVars[name]
                if (sh != null) for (ov in sh.asReversed()) {
                    val ot = ov.type
                    if (ot != null) {
                        val oic = invokeCandidates(typeWithFlowVar(ov, ot))
                        for (c in oic) c.invokeVar = ov
                        if (oic.isNotEmpty()) groups.add(oic)
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
                // `values()` / `valueOf(s)` written in the companion object of an enum class
                if (!s.instance && s.cls.isEnum && (name == "values" || name == "valueOf")) {
                    for (f in sema.members.staticFuns(s.cls, name)) {
                        if (f.kind != FunKind.SYNTH) continue
                        sema.ensureSig(f)
                        groups.add(listOf(Candidate(f, HashMap(), null)))
                    }
                }
                val comp = s.cls.companion
                if (comp != null) addReceiverGroups(comp.thisType(), name, groups, RecvRef(comp, null, true))
                // the companion objects of the superclasses are in scope too
                if (s.instance || !s.cls.isObject) for (site in ops.closure(s.cls.thisType())) {
                    val sc = site.cls.companion
                    if (sc != null && sc !== comp && site.cls !== s.cls) addReceiverGroups(sc.thisType(), name, groups, RecvRef(sc, null, true))
                }
                if (s.cls.isObject && !s.instance) addReceiverGroups(s.cls.thisType(), name, groups, RecvRef(s.cls, null, true))
                val nc = s.cls.nested[name]
                if (nc != null) {
                    groups.add(ctorCandidates(nc))
                    groups.addAll(objectInvokeGroups(nc))
                } else if (s.instance) {
                    // nested and inner classes of the superclasses are in scope too
                    for (site in ops.closure(s.cls.thisType())) {
                        val sn = if (site.cls !== s.cls) site.cls.nested[name] else null
                        if (sn != null) {
                            groups.add(ctorCandidates(sn))
                            break
                        }
                    }
                }
            }
            is ReceiverScope -> if (pass == 1) {
                val nrt2 = narrowRecv(s.type, s.label, !seenRecv)
                val nref2 = RecvRef(null, s, false)
                if (nrt2 !== s.type) nref2.smart = nrt2
                addReceiverGroups(nrt2, name, groups, nref2)
                // `with(outer) { Inner() }`: an inner class of the receiver
                if (nrt2 is ClassType || nrt2 is TypeParamType) for (site in ops.closure(nrt2)) {
                    val nc = site.cls.nested[name]
                    if (nc != null && nc.isInner) groups.add(ctorCandidates(nc))
                }
                seenRecv = true
            }
            is FileScope -> if (pass == 1) {
                for (g in sema.topLevel(file, name)) {
                    val l = ArrayList<Candidate>()
                    for (x in g) {
                        if (x is FunSym && !x.isExtension) {
                            val via = sema.importedVia[x]
                            val c = Candidate(x, sema.importedSubstOf(x), null)
                            if (via != null) c.dispatchRef = RecvRef(via, null, true)
                            l.add(c)
                        }
                        else if (x is ClassSym) l.addAll(ctorCandidates(x))
                        else if (x is TypeAliasSym) {
                            val et = sema.expandAlias(x)
                            if (et is ClassType) {
                                val ata = aliasTypeArgs
                                if (x.typeParams.isNotEmpty() && ata != null && ata.size == x.typeParams.size && !ata.any { it === INFER_TYPE }) {
                                    // `ST<String>(...)` with `typealias ST<T> = Pair<String, T>`: the arguments of the alias fix those of the class
                                    val am = HashMap<TypeParamSym, Type>()
                                    var ai = 0
                                    for (tp in x.typeParams) {
                                        am[tp] = ata[ai]
                                        ai++
                                    }
                                    val fixed = ops.substitute(et, am)
                                    if (fixed is ClassType) for (k in et.cls.ctors) {
                                        val fc = Candidate(k, ops.substOf(fixed), null)
                                        fc.ignoreTypeArgs = true
                                        l.add(fc)
                                    }
                                } else for (k in et.cls.ctors) l.add(Candidate(k, aliasSubst(x, et), null))
                                // the SAM constructor of a fun interface behind the alias (`typealias R = KRunnable; R { }`)
                                if (et.cls.isFunInterface && et.cls.ctors.isEmpty()) {
                                    val asub = aliasSubst(x, et)
                                    if (et.cls.typeParams.isEmpty() || asub.isNotEmpty()) for (k in ctorCandidates(et.cls)) l.add(Candidate(k.fn, asub, null))
                                }
                            }
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
                        } else if (x is ClassSym) {
                            for (g in objectInvokeGroups(x)) il.addAll(g)
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

/** `A(args)` where `A` is an object or a class with a companion object: the `invoke` members and extensions of that object */
fun BodyChecker.objectInvokeGroups(c: ClassSym): List<List<Candidate>> {
    val out = ArrayList<List<Candidate>>()
    val obj = if (c.isObject) c else c.companion
    if (obj == null) return out
    val ref = RecvRef(obj, null, true)
    val mem = ArrayList<Candidate>()
    for (ic in invokeCandidates(obj.thisType())) {
        ic.dispatchRef = ref
        mem.add(ic)
    }
    if (mem.isNotEmpty()) out.add(mem)
    extensionGroups("invoke", obj.thisType(), out, ref)
    return out
}

/** the member extensions named [name] of the implicit receivers that apply to [t] */
fun BodyChecker.implicitMemberExtGroup(name: String, t: Type, groups: ArrayList<List<Candidate>>) {
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
}

/** `f(args)` where the callee name is a value with an `invoke` (enum entry, object behind a type alias, extension property) */
fun BodyChecker.valueInvokeGroups(callee: NameExpr, recv: RecvInfo?): List<List<Candidate>>? {
    val t: Type
    if (recv == null) {
        val r = resolveSimpleName(callee.name) ?: return null
        if (r.kind == NameRes.CLASS) return null
        t = nameResult(callee, r)
    } else if (recv is ClassRecv) {
        sema.members.ensureSynth(recv.cls)
        var found: Type? = null
        for (en in recv.cls.entries) {
            if (en.name == callee.name) {
                found = recv.cls.thisType()
                record(callee, found, en)
            }
        }
        if (found == null) {
            // a property of the object (or the companion object): `Holder.value()` with an extension `invoke`
            val oc = if (recv.cls.isObject) recv.cls else recv.cls.companion
            val res = if (oc != null) memberPropRes(oc.thisType(), callee.name) else null
            if (res != null && oc != null) {
                val cr = record(callee, res.type, res.sym)
                cr.access = Access(RecvUse(null, false, RecvRef(oc, null, true), null), null)
                found = res.type
            }
        }
        t = found ?: return null
    } else if (recv is ValueRecv) {
        if (recv.type is ErrType) return null
        val rt0 = if (recv.safe) ops.makeNotNull(recv.type) else recv.type
        val mp = memberPropRes(rt0, callee.name)
        if (mp != null) {
            // a member property whose value has an extension `invoke`
            val pr = record(callee, mp.type, mp.sym)
            pr.access = Access(RecvUse(recv.expr, recv.safe, null, recv.type), null)
            t = mp.type
        } else {
            val ex = extensionProp(callee.name, recv.type) ?: return null
            val er = record(callee, ex.type, ex.sym)
            er.access = Access(null, RecvUse(recv.expr, recv.safe, null, recv.type))
            t = ex.type
        }
    } else return null
    if (t is ErrType) return null
    val groups = ArrayList<List<Candidate>>()
    val mem = invokeCandidates(t)
    if (mem.isNotEmpty()) groups.add(mem)
    implicitMemberExtGroup("invoke", t, groups)
    extensionGroups("invoke", t, groups)
    for (g in groups) for (c in g) c.invokeCallee = true
    return groups
}

/** the type arguments of a class behind a typealias without own type parameters (`typealias SC = KConsumer<String>`) */
fun BodyChecker.aliasSubst(a: TypeAliasSym, et: ClassType): Map<TypeParamSym, Type> = if (a.typeParams.isEmpty()) ops.substOf(et) else HashMap()

fun BodyChecker.localReceiverFunVar(name: String): Boolean {
    val vr = resolveSimpleName(name) ?: return false
    if (vr.sym !is VarSym) return false
    val vt = vr.type
    val ft = ops.asFunType(vt) ?: vt
    return ft is FunType && ft.receiver != null
}

/** members of an implicit receiver, then top-level extensions applicable to it */
fun BodyChecker.addReceiverGroups(rt: Type, name: String, groups: ArrayList<List<Candidate>>, ref: RecvRef? = null) {
    // `fun (() -> String).f() = invoke()`: the receiver is a function value
    if (name == "invoke" && ref != null && (rt is FunType || ops.asFunType(rt) != null)) {
        val ic = invokeCandidates(rt)
        if (ic.isNotEmpty()) {
            for (c in ic) c.dispatchRef = ref
            groups.add(ic)
            return
        }
    }
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
        // a property whose type is being inferred cannot be the callee (`val ok = ok()` calls the function `ok`)
        if (mp.prop.typeState == 1) continue
        val pt = ops.substitute(sema.typeOfProp(mp.prop), mp.subst)
        for (ic in invokeCandidates(pt)) {
            ic.invokeProp = mp.prop
            ic.invokePropRecv = ref
            il.add(ic)
        }
    }
    if (il.isNotEmpty()) groups.add(il)
    // member extensions of the receiver applied to an implicit receiver: `fun NoiseMaker.playWith()` declared in `Doggy`, called as `playWith()` in a lambda with receiver
    if (ref != null) {
        var any = false
        // (a member extension whose result type is being inferred is not a candidate of a call in its own body: `override fun A.f() = f(x)` calls the parameter `f`)
        for (m in members.funsNamed(rt, name)) if (m.fn.decl?.receiver != null && m.fn.retState != 1) any = true
        // a local variable of type `Recv.() -> R` of the same name is closer than the member extension (`encode(value)` calls the parameter)
        if (any && localReceiverFunVar(name)) any = false
        if (any) for (r in implicitReceivers()) {
            val l = ArrayList<Candidate>()
            for (m in members.funsNamed(rt, name)) {
                if (m.fn.decl?.receiver == null || m.fn.retState == 1) continue
                val c = Candidate(m.fn, m.subst, r.type)
                c.dispatchRef = ref
                c.extRef = r.ref
                l.add(c)
            }
            if (l.isNotEmpty()) groups.add(l)
        }
    }
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
            if (found == pos) pos = found + 1 // `f(a = 1, 2)`: a named argument in its own position (also a vararg given by name: `f(p1 = *a, "3")`)
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
    inheritDefaults(c.fn)
    return c.fn.params
}

/** An overriding function has the default values of the function it overrides (`override fun f(a: Int)` of `open fun f(a: Int = 1)`). */
fun BodyChecker.inheritDefaults(fn: FunSym) {
    if (fn.defaultsInherited) return
    fn.defaultsInherited = true
    val o = fn.owner ?: return
    if (!fn.has("override") || fn.params.isEmpty()) return
    for (site in ops.closure(o.thisType())) {
        if (site.cls === o) continue
        sema.members.ensureSynth(site.cls)
        val l = site.cls.funs[fn.name] ?: continue
        for (g in l) {
            sema.ensureSig(g)
            if (g.params.size != fn.params.size) continue
            inheritDefaults(g)
            var j = 0
            while (j < g.params.size) {
                if (g.params[j].hasDefault) fn.params[j].hasDefault = true
                j++
            }
        }
    }
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
            if (pt.cls.isFunInterface) {
                // `compareBy(comparator, selector)` against `compareBy({ it.a }, { it.b })`: a lambda without parameters cannot be a two-argument SAM
                val sf = samFunType(pt) ?: return true
                if (u is LambdaExpr) return if (u.hasArrow) u.params.size == sf.params.size else sf.params.size <= 1
                if (u is AnonFunExpr) return u.fn.params.size == sf.params.size
                return true
            }
            return false
        }
        if (pt is CapType) {
            // `Container<in Element>.add(arg: T)`: a lambda has the shape of the lower bound
            val lb = pt.arg.type
            return pt.arg.variance == Variance.IN && lb != null && (lb is FunType || (lb is ClassType && lb.cls.isFunInterface)) && shapeOk(arg, lb)
        }
        // a rigid type parameter (the enclosing function's own `T`) is no target of a lambda: `transform { }` with a local `transform: (T) -> R` falls through to the extension
        return false
    }
    if (u is CallableRefExpr && u.name != "class" && u.receiver == null && pt is FunType) {
        // `f(::g)` with overloads of `f` over different function arities: the reference must have the arity of the parameter type
        val cands = ArrayList<RefCand>()
        for (g in unqualifiedGroups(u.name)) for (c in g) {
            // an extension of an implicit receiver (`with(a) { call(::ext) }`) is bound to it: the receiver is no parameter
            val er0 = c.extRecv
            if (c.extRef != null && er0 != null && !extFits(c.fn, er0)) continue
            funRefCand(c, c.extRef != null, cands)
        }
        if (cands.isEmpty()) return true
        val want = pt.params.size + (if (pt.receiver != null) 1 else 0)
        for (c in cands) {
            if (c.params.size == want) return true
            val fn = c.sym as? FunSym ?: continue
            if (c.params.size == fn.params.size && adaptMapping(fn.params, 0, want) != null) return true
        }
        return false
    }
    return true
}

fun BodyChecker.tryCandidate(c: Candidate, args: List<Arg>, typeArgs0: List<Type>?): Attempt? {
    val open = ArrayList(sema.openVars)
    if (open.isEmpty()) return tryCandidate0(c, args, typeArgs0)
    val sizes = IntArray(open.size * 3)
    for ((k, v) in open.withIndex()) {
        sizes[k * 3] = v.lower.size
        sizes[k * 3 + 1] = v.upper.size
        sizes[k * 3 + 2] = v.equal.size
    }
    val att = tryCandidate0(c, args, typeArgs0)
    val delta = ArrayList<Triple<TypeVariable, Int, Type>>()
    for ((k, v) in open.withIndex()) {
        for (j in 0..2) {
            val l = if (j == 0) v.lower else if (j == 1) v.upper else v.equal
            while (l.size > sizes[k * 3 + j]) {
                delta.add(Triple(v, j, l.removeAt(l.size - 1)))
            }
        }
    }
    delta.reverse()
    if (att != null) att.openDelta = delta
    return att
}

fun BodyChecker.tryCandidate0(c: Candidate, args: List<Arg>, typeArgs0: List<Type>?): Attempt? {
    val fn = c.fn
    val typeArgs = if (c.ignoreTypeArgs) null else typeArgs0
    val params = paramsOf(c)
    val binding = bindArgs(params, args) ?: return null
    val tps = typeParamsOf(fn)
    val subst = HashMap<TypeParamSym, Type>()
    subst.putAll(c.subst)
    val cs = ConstraintSystem(sema)
    if (typeArgs != null && typeArgs.isNotEmpty()) {
        if (typeArgs.size != tps.size) return null
        var i = 0
        if (typeArgs.any { it === INFER_TYPE }) {
            // `foo<Int, _>(...)`: the others are given, the `_` ones are inferred
            cs.addVariables(tps, subst)
            for (tp in tps) {
                if (typeArgs[i] !== INFER_TYPE && !cs.addEqual(subst[tp] ?: ErrType("type argument"), typeArgs[i])) return null
                i++
            }
        } else for (tp in tps) {
            subst[tp] = typeArgs[i]
            i++
        }
    } else {
        // the type parameters the candidate already instantiates (`Base<Long>(1)` in a super constructor call) are not inferred
        cs.addVariables(if (c.subst.isEmpty()) tps else tps.filter { !c.subst.containsKey(it) }, subst)
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
        val at0 = a.type
        if (p.isVararg && !a.spread && a.name != null && at0 != null && !a.postponed) {
            // `foo(s = array)`: an array given by name to a vararg parameter is its value (no `*` needed)
            val el = ops.substitute(p.varargElement ?: pty, subst)
            val arrT = ops.substitute(pty, subst)
            if (!cs.hasVars(el) && !cs.hasVars(arrT) && !ops.isSubtype(at0, el) && ops.isSubtype(at0, arrT)) a.spread = true
        }
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

/** are the context parameters of [f] available at the call site: an implicit receiver or a variable of the type of each */
fun BodyChecker.contextsInScope(f: FunSym): Boolean {
    val cps = f.decl?.mods?.contextParams ?: return true
    for (cp in cps) {
        val t = if (cp.type != null) sema.resolveType(cp.type, sema.funScope(f)) else return false
        var found = implicitReceivers().any { ops.isSubtype(it.type, t) }
        var s: Scope? = scope
        while (!found && s != null) {
            // (a context parameter of the enclosing function: a parameter without a declaration of its own)
            if (s is LocalScope) for (v in s.vars.values) { val vt = v.type; if (v.isParam && v.decl == null && v.name != "it" && vt != null && ops.isSubtype(vt, t)) found = true }
            s = s.parent
        }
        if (!found) return false
    }
    return true
}

/** [a] has the same number of type parameters as [b] and its upper bounds are subtypes of the bounds of [b] at every position, strictly at one */
fun BodyChecker.tpBoundsTighter(a: Attempt, b: Attempt): Boolean {
    if (a.tps.size != b.tps.size) return false
    var strict = false
    var k = 0
    while (k < a.tps.size) {
        sema.ensureBounds(a.tps[k])
        sema.ensureBounds(b.tps[k])
        val ba = a.tps[k].bounds
        val bb = b.tps[k].bounds
        // every bound of b must be implied by one of a
        for (x in bb) if (!ba.any { y -> ops.isSubtype(y, x) }) return false
        if (ba.size > bb.size || ba.any { y -> bb.none { x -> ops.isSubtype(y, x) && ops.isSubtype(x, y) } }) strict = true
        k++
    }
    return strict
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
    if (pool.size > 1 && pool.any { it.cand.fn.decl?.mods?.contextParams?.isNotEmpty() == true }) {
        // overloads that differ in their context parameters (`foo()` and `context(Scope) foo()`): the contextual one when its contexts are in scope
        val withCtx = pool.filter { it.cand.fn.decl?.mods?.contextParams?.isNotEmpty() == true && contextsInScope(it.cand.fn) }
        val without = pool.filter { it.cand.fn.decl?.mods?.contextParams?.isNotEmpty() != true }
        if (withCtx.isNotEmpty()) pool = withCtx else if (without.isNotEmpty()) pool = without
    }
    if (pool.size > 1 && pool.all { it.tps.isNotEmpty() }) {
        // overloads that differ only in the bounds of their type parameters (`<T1, T2 : B>` over `<T1, T2>`): the one with the tighter bounds
        val tight = pool.filter { a -> pool.all { b -> a === b || tpBoundsTighter(a, b) } }
        if (tight.size == 1) return tight[0]
    }
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
    if (pool.size > 1 && pool.all { it.tps.isEmpty() }) {
        // the same member of several bounds / supertypes (`T : A, T : B`): the one with the most specific result type
        val rets = pool.map { ops.substitute(sema.returnTypeOf(it.cand.fn), it.cand.subst) }
        for (i in pool.indices) {
            var most = true
            for (j in rets.indices) if (j != i && !ops.isSubtype(rets[i], rets[j])) most = false
            if (most) return pool[i]
        }
    }
    if (pool.size > 1) {
        // a member of a subclass over the same signature of its supertype was already filtered; prefer non-abstract
        for (a in pool) if (!a.cand.fn.isAbstract) return a
    }
    return pool[0]
}

// ---- resolving a call against candidate groups --------------------------------------------------------------------------------------

/** false when an extension candidate's receiver class cannot be the call's receiver (decided on the classes only, so overloads such as `Array<T>.f` / `MutableList<T>.f` do not hide each other's expected types) */
fun BodyChecker.receiverClassFits(c: Candidate): Boolean {
    val fr = c.fn.receiver
    val er = c.extRecv
    if (fr is ClassType && er is ClassType) return ops.asSuper(er, fr.cls) != null
    return true
}

fun BodyChecker.analyzeArgs(args: List<Arg>, groups: List<List<Candidate>>, typeArgs: List<Type>?, expected: Type? = null) {
    // the parameter types of the binding candidates are the expected types of the arguments (several candidates: where they agree)
    val cands = ArrayList<Candidate>()
    for (g in groups) {
        for (c in g) {
            val params = paramsOf(c)
            if (bindArgs(params, args) != null && receiverClassFits(c)) cands.add(c)
        }
        if (cands.isNotEmpty()) break
    }
    var expectedOf: Array<Type?> = arrayOfNulls(args.size)
    if (cands.size == 1) {
        expectedOf = argExpectations(cands[0], args, typeArgs, expected)
    } else if (cands.size > 1 && expected != null && cands.size <= 4) {
        // overloads that all take the argument as a bare type parameter (`listOf(x)`, `listOf(vararg x)`): the expected type of the call decides for all of them
        var all: Array<Type?>? = null
        for (c in cands) {
            val ex = argExpectations(c, args, typeArgs, expected)
            val prev = all
            if (prev == null) {
                all = ex
            } else {
                var k = 0
                while (k < prev.size) {
                    val a = prev[k]
                    val b = ex[k]
                    if (a != null && (b == null || !(ops.isSubtype(a, b) && ops.isSubtype(b, a)))) prev[k] = null
                    k++
                }
            }
        }
        if (all != null) expectedOf = all
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.expr != null && a.type == null && !a.postponed) {
            argNode = AstWalk.unparen(a.expr)
            val savedInArg = inArgCall
            inArgCall = true
            val t = expr(a.expr, expectedOf[i])
            inArgCall = savedInArg
            argNode = null
            a.type = t
        }
        i++
    }
}

/** The expected types of the arguments of a call of [single]: its parameter types, as far as no unsolved type parameter is left in them. */
fun BodyChecker.argExpectations(single: Candidate, args: List<Arg>, typeArgs: List<Type>?, expected: Type?): Array<Type?> {
    val expectedOf: Array<Type?> = arrayOfNulls(args.size)
    val params = paramsOf(single)
    val b = bindArgs(params, args) ?: return expectedOf
    val tps = typeParamsOf(single.fn)
    // type parameters the receiver already determines are known for the arguments
    val cs = ConstraintSystem(sema)
    val m = HashMap<TypeParamSym, Type>()
    m.putAll(single.subst)
    if (typeArgs != null && typeArgs.isNotEmpty() && typeArgs.size == tps.size && typeArgs.none { it === INFER_TYPE }) {
        // explicit type arguments (`listOf<ULong>(1u)`): they are the expectation of the arguments
        var j = 0
        for (tp in tps) {
            m[tp] = typeArgs[j]
            j++
        }
    } else if (tps.isNotEmpty()) {
        cs.addVariables(if (single.subst.isEmpty()) tps else tps.filter { !single.subst.containsKey(it) }, m)
        val fr = single.fn.receiver
        val er = single.extRecv
        if (fr != null && er != null) cs.addSub(er, ops.substitute(fr, m))
        // the expected type of the call bounds the type parameters of the result (`val l: List<Box<Any?>> = listOf(Box(0))`)
        if (expected != null && single.invokeType == null && single.fn.kind != FunKind.CONSTRUCTOR && !ops.isNothing(expected)) {
            val rt = ops.substitute(sema.returnTypeOf(single.fn), m)
            if (rt is ClassType && cs.hasVars(rt)) cs.addSub(rt, expected)
        }
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
    return expectedOf
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

private fun hasLowPriority(m: Modifiers?): Boolean {
    if (m == null) return false
    for (i in m.items) {
        if (i !is AnnotationMod) continue
        val t = i.annotation.type
        if (t is UserType && t.segments.lastOrNull()?.name == "LowPriorityInOverloadResolution") return true
    }
    return false
}

private fun isLowPriority(f: FunSym): Boolean {
    if (hasLowPriority(f.decl?.mods)) return true
    if (f.secondary != null) return hasLowPriority(f.secondary!!.mods)
    return f.isPrimary && hasLowPriority(f.owner?.decl?.primaryCtor?.mods)
}

fun BodyChecker.resolveCallGroups(
    node: Ast,
    groups: List<List<Candidate>>,
    args: List<Arg>,
    typeArgs: List<Type>?,
    expected: Type?,
    analyzed: Boolean,
): CallRes? {
    if (!analyzed) analyzeArgs(args, groups, typeArgs, expected)
    for (g in groups) {
        val ok = ArrayList<Attempt>()
        for (c in g) {
            val a = tryCandidate(c, args, typeArgs)
            if (a != null) ok.add(a)
        }
        if (ok.isNotEmpty()) {
            // `@LowPriorityInOverloadResolution`: such a candidate only wins when it is the only applicable one
            val ranked = if (ok.size > 1) ok.filter { !isLowPriority(it.cand.fn) }.ifEmpty { ok } else ok
            val pick = if (ranked.size > 1) byLambdaResult(ranked, args) else ranked
            val best = chooseMostSpecific(pick, args)
            return completeCall(best, args, expected)
        }
    }
    leftoverArgs(args)
    return null
}

/**
 * Overloads that differ only in the result type of a lambda parameter (`sumOf { ... }` for Int, Long and Double): the lambda is
 * analysed once without an expected result type, and the overloads that accept its result remain.
 */
fun BodyChecker.byLambdaResult(ok: List<Attempt>, args: List<Arg>): List<Attempt> {
    var li = -1
    var i = 0
    while (i < args.size) {
        if (args[i].postponed) {
            if (li >= 0) return ok
            li = i
        }
        i++
    }
    if (li < 0) return ok
    val lam = unwrapPostponed(args[li].expr ?: return ok) as? LambdaExpr ?: return ok
    val first = ok[0]
    for (a in ok) {
        if (a.cand.fn.name != first.cand.fn.name || a.cand.fn.owner !== first.cand.fn.owner) return ok
        if (a.argParam[li] !is FunType) return ok
        // `compareBy(selector)` / `compareBy(vararg selectors)`: the overloads differ in the shape of the parameters, not in the lambda's result
        if (a.params[a.binding.paramOfArg[li]].isVararg) return ok
    }
    val cs = first.cs
    val ft = cs.resolveFixed(first.argParam[li] ?: return ok) as? FunType ?: return ok
    for (a in ok) {
        val f = a.cs.resolveFixed(a.argParam[li]!!) as FunType
        if (f.params.size != ft.params.size) return ok
    }
    cs.fixVarsOf(FunType(ft.receiver, ft.params, sema.unitType, false, false))
    val ef = cs.resolveFixed(FunType(ft.receiver, ft.params, sema.nullableAny, false, false)) as? FunType ?: return ok
    probeLambda = true
    inlineLambdaNext = isInlinableParam(first.cand.fn, first.params[first.binding.paramOfArg[li]])
    val lt = analyzeLambda(lam, ef, cs) as? FunType ?: return ok
    val res = lt.ret
    if (containsError(res)) return ok
    val keep = ArrayList<Attempt>()
    for (a in ok) {
        val f = a.cs.resolveFixed(a.argParam[li]!!) as FunType
        if (ops.isSubtype(res, f.ret)) keep.add(a)
    }
    return if (keep.isEmpty()) ok else keep
}

/** is a lambda argument of [p] expanded at the call site: [fn] is a non-suspend `inline` function and [p] neither noinline nor crossinline */
fun isInlinableParam(fn: FunSym, p: VarSym): Boolean = fn.isInline && !p.isNoinline && !p.isCrossinline

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
    // a builder lambda of a call that is an argument of another call is only postponed inside another builder lambda (K2 completes both together)
    val mayPostpone = !inArgCall || sema.openVars.isNotEmpty()
    for ((v, kind, t) in att.openDelta) (if (kind == 0) v.lower else if (kind == 1) v.upper else v.equal).add(t)
    lambdaLabel = if (fn.kind == FunKind.CONSTRUCTOR) fn.owner?.name ?: fn.name else fn.name
    var ret: Type
    val it = att.cand.invokeType
    if (it != null) {
        ret = it.ret
    } else if (fn.kind == FunKind.CONSTRUCTOR) {
        ret = ops.substitute(fn.owner!!.thisType(), att.subst)
    } else {
        ret = ops.substitute(sema.returnTypeOf(fn), att.subst)
    }
    // (the last statement of a `() -> Unit` lambda is not bound by Unit: its type may still be a postponed variable)
    if (expected != null && cs.hasVars(ret) && !(sema.openVars.isNotEmpty() && ops.isUnit(expected) && !expected.nullable)) {
        cs.addSub(ret, expected)
    }
    var i = 0
    var builderStart = -1
    while (i < args.size) {
        val a = args[i]
        if (a.postponed) {
            val pt0 = cs.resolveFixed(att.argParam[i] ?: ErrType("param"))
            // `Container<in Element>.add(arg: T)`: a lambda is converted to the lower bound of the captured type
            val capLb = if (pt0 is CapType && pt0.arg.variance == Variance.IN) pt0.arg.type else null
            val pt: Type = if (capLb != null && (capLb is FunType || (capLb is ClassType && capLb.cls.isFunInterface))) capLb else pt0
            val e = a.expr!!
            val u = unwrapPostponed(e)
            var expectedFun: FunType? = null
            if (pt is FunType) expectedFun = pt
            else if (pt is ClassType && pt.cls.isFunInterface) expectedFun = samFunType(pt)
            else if (pt is VarType && pt.v.fixed == null && u is LambdaExpr) {
                // `val l: List<(Int) -> Boolean> = listOf({ it > 0 })`: the expected type bounds the element type
                val bound = pt.v.equal.firstOrNull { it is FunType } ?: pt.v.upper.firstOrNull { it is FunType }
                if (bound is FunType) expectedFun = bound
            }
            if (u is LambdaExpr) {
                if (expectedFun != null) {
                    // the declared parameter types of the lambda bound the type variables of the expected ones (`foldl(0) { e: Int, acc: Long -> acc + e }`: A <: Long)
                    if (u.params.isNotEmpty() && u.params.size == expectedFun.params.size) {
                        var pk = 0
                        for (lp in u.params) {
                            val dty = lp.type
                            if (dty != null && lp.names == null) cs.addSub(expectedFun.params[pk], sema.resolveType(dty, scope))
                            pk++
                        }
                    }
                    // builder inference: a variable nobody has constrained that is a type argument of the receiver / a parameter
                    // (`MutableList<E>.() -> Unit`) stays open while the body is checked; the calls in the body constrain it
                    val postponedVars = ArrayList<TypeVariable>()
                    if (mayPostpone) {
                        val cand = ArrayList<TypeVariable>()
                        val er = expectedFun.receiver
                        if (er != null) cs.nestedVars(cs.resolveFixed(er), true, cand)
                        for (ep in expectedFun.params) cs.nestedVars(cs.resolveFixed(ep), true, cand)
                        for (v in cand) if (v.equal.isEmpty() && v.lower.all { ops.isNothing(it) } && cs.vars.contains(v) && !sema.openVars.contains(v)) postponedVars.add(v)
                    }
                    sema.openVars.addAll(postponedVars)
                    cs.fixVarsOf(FunType(expectedFun.receiver, expectedFun.params, sema.unitType, false, false), postponedVars)
                    // result variables that the other arguments determine exactly (`getOrPut(k) { HashSet() }`) are known too
                    val rv = ArrayList<TypeVariable>()
                    cs.unfixedVars(cs.resolveFixed(expectedFun.ret), rv)
                    for (v in rv) if (v.equal.isNotEmpty() || v.lower.any { !ops.isNothing(it) }) cs.fix(v) // a lower bound of Nothing (the first lambda returns non-locally) determines nothing
                    val ef = cs.resolveFixed(expectedFun) as FunType
                    inlineLambdaNext = isInlinableParam(fn, att.params[att.binding.paramOfArg[i]])
                    pendingLabel = postponedLabel(e)
                    val recStart = rec.order.size
                    val lt = analyzeLambda(u, ef, cs)
                    sema.openVars.removeAll(postponedVars)
                    if (postponedVars.isNotEmpty()) {
                        builderStart = if (builderStart < 0) recStart else minOf(builderStart, recStart)
                    }
                    cs.addSub(lt, pt)
                    // the lambda is recorded with the final types later
                } else {
                    pendingLabel = postponedLabel(e)
                    val lt = analyzeLambda(u, null, cs)
                    cs.addSub(lt, pt)
                }
            } else if (u is AnonFunExpr) {
                inlineLambdaNext = isInlinableParam(fn, att.params[att.binding.paramOfArg[i]])
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
    if (builderStart >= 0) zonkRecords(builderStart, cs)
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

fun BodyChecker.samFunType(t: ClassType): FunType? = ops.samFunType(t)

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
fun BodyChecker.resolveOperator(name: String, recv0: Type, argTypes: List<Type>, pos: Int, expected: Type?, lastArg: Expr? = null): OpResult? {
    var recv = recv0
    if (recv is ErrType) return null
    val args = ArrayList<Arg>()
    for ((i, t) in argTypes.withIndex()) {
        // a lambda / callable reference value (`a[i] = { ... }`) is analysed against the parameter type of `set`
        val postponed = lastArg != null && i == argTypes.size - 1
        val a = Arg(if (postponed) lastArg else null, null, false)
        if (!postponed) a.type = t
        args.add(a)
    }
    // `a[i] = v`: the value is the last parameter of `set`, parameters with defaults and a vararg may stand before it
    if (name == "set" && args.size > 1) args[args.size - 1].trailing = true
    var groups = memberAndExtensionGroups(recv, name, true)
    if (name == "matmul" || name == "matmulAssign") {
        // Mini's `@` convention: only functions declared `operator` take part (no stdlib type has one)
        val only = ArrayList<List<Candidate>>()
        for (g in groups) {
            val c = g.filter { it.fn.isOperator }
            if (c.isNotEmpty()) only.add(c)
        }
        groups = only
    }
    val res = resolveCallGroups(BodyDummy.node, groups, args, null, expected, true) ?: return null
    val r = OpResult(res.ret, res.callee, res.calleeText, res.paramTypes)
    r.info = res.info
    return r
}

object BodyDummy {
    val node: Ast = LiteralExpr(LitKind.NULL, "")
}

/** replaces the type variables that were fixed after the body of a builder lambda was checked in every record made since [from] */
fun BodyChecker.zonkRecords(from: Int, cs: ConstraintSystem) {
    fun z(t: Type): Type = cs.resolveFixed(t)
    fun zn(t: Type?): Type? = if (t == null) null else cs.resolveFixed(t)
    fun zv(v: VarSym) {
        val t = v.type
        if (t != null) v.type = z(t)
    }
    fun zr(u: RecvUse?): RecvUse? = if (u == null || u.type == null) u else RecvUse(u.expr, u.safe, u.ref, z(u.type))
    val order = rec.order
    var i = from
    while (i < order.size) {
        val r = rec.get(order[i])
        if (r == null) {
            i++
            continue
        }
        r.type = z(r.type)
        r.smart = zn(r.smart)
        r.target = zn(r.target)
        r.baseType = zn(r.baseType)
        val c = r.callee
        if (c is VarSym) zv(c)
        val ex = r.extra
        if (ex is LambdaInfo) for (p in ex.params) zv(p)
        val ci = r.call
        if (ci != null) {
            ci.typeArgs = ci.typeArgs.map { z(it) }
            ci.ret = zn(ci.ret)
            ci.paramTypes = ci.paramTypes.map { zn(it) }
            ci.dispatch = zr(ci.dispatch)
            ci.ext = zr(ci.ext)
            ci.invokeValue = zr(ci.invokeValue)
            for (a in ci.args) if (a.type != null) a.type = zn(a.type)
        }
        val ac = r.access
        if (ac != null) {
            ac.dispatch = zr(ac.dispatch)
            ac.ext = zr(ac.ext)
        }
        i++
    }
}
