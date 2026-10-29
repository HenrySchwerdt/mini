package mini.frontend.sema

import mini.frontend.*

/*
 * Names: simple names, qualified access (`a.b`), calls. The scope walk follows the tower order of K2:
 * locals and class scopes from the innermost outwards (members of implicit receivers, companions, enum entries),
 * then top-level scopes in priority order; variables win over classifiers.
 */

class NameRes(val sym: Sym?, val type: Type, val classifier: ClassSym?, val kind: Int) {
    companion object {
        const val VALUE = 0
        const val CLASS = 1
        const val OBJECT = 2
    }
}

fun BodyChecker.typeWithFlowVar(v: VarSym, base: Type): Type {
    if (v.isVar && v.capturedAndModified) return base
    val f = flow.facts["v" + v.id] ?: return base
    return ops.narrow(base, f.types)
}

fun BodyChecker.propBaseType(p: PropSym, subst: Map<TypeParamSym, Type>): Type = ops.substitute(sema.typeOfProp(p), subst)

/** extension properties named [name] applicable to [recv] (top-level scopes) */
fun BodyChecker.extensionProp(name: String, recv: Type): NameRes? {
    for (g in sema.topLevel(file, name)) {
        for (x in g) {
            if (x is PropSym && x.receiver != null) {
                sema.ensurePropSig(x)
                val r = x.receiver!!
                val cs = ConstraintSystem(sema)
                val m = HashMap<TypeParamSym, Type>()
                cs.addVariables(x.typeParams, m)
                if (cs.addSub(recv, ops.substitute(r, m))) {
                    cs.fixAll()
                    val t = cs.resolveFixed(ops.substitute(sema.typeOfProp(x), m))
                    return NameRes(x, t, null, NameRes.VALUE)
                }
            }
        }
    }
    return null
}

fun BodyChecker.memberPropRes(rt: Type, name: String): NameRes? {
    for (mp in members.propsNamed(rt, name)) {
        if (mp.prop.receiver != null) continue
        return NameRes(mp.prop, propBaseType(mp.prop, mp.subst), null, NameRes.VALUE)
    }
    return null
}

fun BodyChecker.valueOfClass(c: ClassSym): NameRes {
    if (c.isObject) return NameRes(null, c.thisType(), c, NameRes.OBJECT)
    return NameRes(null, ErrType("class as value"), c, NameRes.CLASS)
}

/** a simple name used as an expression: variables first, then classifiers */
fun BodyChecker.resolveSimpleName(name: String): NameRes? {
    var ls: Scope? = scope
    while (ls != null) {
        if (ls is LocalScope) {
            val v = ls.vars[name]
            if (v != null) return NameRes(v, v.type ?: ErrType("untyped"), null, NameRes.VALUE)
        }
        ls = ls.parent
    }
    var s: Scope? = scope
    while (s != null) {
        when (s) {
            is ClassScope -> {
                if (s.instance) {
                    val r = memberPropRes(s.cls.thisType(), name)
                    if (r != null) return r
                }
                val comp = s.cls.companion
                if (comp != null) {
                    val r = memberPropRes(comp.thisType(), name)
                    if (r != null) return r
                }
                if (s.cls.isObject && !s.instance) {
                    val r = memberPropRes(s.cls.thisType(), name)
                    if (r != null) return r
                }
                sema.members.ensureSynth(s.cls)
                for (en in s.cls.entries) if (en.name == name) return NameRes(en, s.cls.thisType(), null, NameRes.VALUE)
            }
            is ReceiverScope -> {
                val r = memberPropRes(s.type, name)
                if (r != null) return r
            }
            else -> {}
        }
        s = s.parent
    }
    for (r in implicitReceivers()) {
        val x = extensionProp(name, r.type)
        if (x != null) return x
    }
    for (g in sema.topLevel(file, name)) {
        for (x in g) {
            if (x is PropSym && x.receiver == null) {
                return NameRes(x, sema.typeOfProp(x), null, NameRes.VALUE)
            }
            if (x is EnumEntrySym) return NameRes(x, x.owner.thisType(), null, NameRes.VALUE)
        }
    }
    // classifiers (objects, companions as qualifiers)
    val c = sema.lookupClassifier(scope, name)
    if (c is ClassSym) return valueOfClass(c)
    if (c is TypeAliasSym) {
        val et = sema.expandAlias(c)
        if (et is ClassType) return valueOfClass(et.cls)
    }
    return null
}

fun BodyChecker.nameExpr(e: NameExpr, expected: Type?): Type {
    val r = resolveSimpleName(e.name)
    if (r == null) {
        err(e.start, "unresolved reference '" + e.name + "'")
        return ErrType("unresolved " + e.name)
    }
    return nameResult(e, r)
}

/** records the result of a name resolution on [node] and returns the type with smart casts applied */
fun BodyChecker.nameResult(node: Expr, r: NameRes): Type {
    when (r.kind) {
        NameRes.CLASS -> {
            val c = r.classifier!!
            val ct = classQualifierType(c)
            record(node, ct, null)
            return ct
        }
        NameRes.OBJECT -> {
            val rr = record(node, r.type, null)
            return r.type
        }
    }
    val sym = r.sym
    val base = r.type
    val rr = record(node, base, sym)
    var res = base
    if (sym is VarSym) {
        res = typeWithFlowVar(sym, base)
    } else if (sym is PropSym) {
        val k = stableKey(node)
        if (k != null) {
            val f = flow.facts[k.key]
            if (f != null) res = ops.narrow(base, f.types)
        }
    }
    if (res !== base) rr.smart = res
    return res
}

fun BodyChecker.classQualifierType(c: ClassSym): Type {
    if (c.isObject) return c.thisType()
    val comp = c.companion
    if (comp != null) return comp.thisType()
    return sema.unitType
}

// ---- qualified expressions ----------------------------------------------------------------------------------------------------------------

fun BodyChecker.isPackagePrefix(name: String): Boolean {
    if (sema.packages.containsKey(name)) return true
    val p = "$name."
    for (k in sema.packages.keys) if (k.startsWith(p)) return true
    return false
}

/** the meaning of the receiver part of a dot expression */
fun BodyChecker.resolveReceiver(r0: Expr, selectorName: String?): RecvInfo? {
    val r = AstWalk.unparen(r0)
    if (r is NameExpr) {
        val res = resolveSimpleName(r.name)
        if (res != null) {
            if (res.kind == NameRes.CLASS) {
                val c = res.classifier!!
                record(r, classQualifierType(c), null)
                return ClassRecv(c)
            }
            val t = nameResult(r, res)
            return ValueRecv(t, r, false)
        }
        if (isPackagePrefix(r.name)) return PkgRecv(r.name)
        err(r.start, "unresolved reference '" + r.name + "'")
        record(r, ErrType("unresolved"), null)
        return null
    }
    if (r is DotExpr && r.selector is NameExpr) {
        val inner = resolveReceiverChain(r)
        if (inner != null) return inner
        return null
    }
    val t = fin(expr(r0, null))
    return ValueRecv(t, r0, false)
}

/** `a.b` where a may be a package or a class: returns the qualifier, or a value receiver after resolving */
fun BodyChecker.resolveReceiverChain(d: DotExpr): RecvInfo? {
    val sel = d.selector as NameExpr
    val base = resolveReceiver(d.receiver, sel.name) ?: return null
    when (base) {
        is PkgRecv -> {
            val full = base.name + "." + sel.name
            val pi = sema.packages[base.name]
            if (pi != null) {
                val c = pi.classes[sel.name]
                if (c != null) {
                    val ct = classQualifierType(c)
                    record(d, ct, null)
                    return ClassRecv(c)
                }
                val ps = pi.props[sel.name]
                if (ps != null && ps.isNotEmpty()) {
                    val p = ps[0]
                    val t = sema.typeOfProp(p)
                    record(d, t, p)
                    return ValueRecv(t, d, false)
                }
            }
            if (isPackagePrefix(full)) return PkgRecv(full)
            err(d.start, "unresolved reference '" + sel.name + "'")
            return null
        }
        is ClassRecv -> {
            val nc = sema.nestedClassifier(base.cls, sel.name, 0)
            if (nc is ClassSym) {
                val ct = classQualifierType(nc)
                record(d, ct, null)
                return ClassRecv(nc)
            }
            val t = selectProperty(d, base, sel, false)
            if (t == null) return null
            return ValueRecv(t, d, false)
        }
        is ValueRecv -> {
            val t = selectProperty(d, base, sel, d.safe)
            if (t == null) return null
            return ValueRecv(t, d, false)
        }
    }
}

/** property / enum entry access `recv.name`; records [node] */
fun BodyChecker.selectProperty(node: DotExpr, recv: RecvInfo, sel: NameExpr, safe: Boolean): Type? {
    val name = sel.name
    when (recv) {
        is ValueRecv -> {
            var rt = recv.type
            if (rt is ErrType) return rt
            val saf = safe || recv.safe
            if (saf) rt = ops.makeNotNull(rt)
            var res = memberPropRes(rt, name)
            if (res == null) res = extensionProp(name, rt)
            if (res == null) {
                // member of an object / companion reached through a class-typed value
                err(sel.start, "unresolved reference '" + name + "'")
                return ErrType("unresolved " + name)
            }
            var t = res.type
            if (saf) t = t.withNullable(true)
            val rr = record(node, t, res.sym)
            // smart casts on stable member chains
            val k = stableKey(node)
            if (k != null && !saf) {
                val f = flow.facts[k.key]
                if (f != null) {
                    val n = ops.narrow(t, f.types)
                    if (n !== t) {
                        rr.smart = n
                        return n
                    }
                }
            }
            return t
        }
        is ClassRecv -> {
            val c = recv.cls
            sema.members.ensureSynth(c)
            for (en in c.entries) {
                if (en.name == name) {
                    val t = c.thisType()
                    record(node, t, en)
                    return t
                }
            }
            var res = memberPropRes(c.thisType(), name)
            val comp = c.companion
            if (res == null && comp != null) res = memberPropRes(comp.thisType(), name)
            if (res == null) {
                val ep = c.props[name]
                if (ep != null && ep.isNotEmpty() && c.isObject) {
                    val p = ep[0]
                    res = NameRes(p, sema.typeOfProp(p), null, NameRes.VALUE)
                }
            }
            if (res == null) {
                err(sel.start, "unresolved reference '" + name + "'")
                return ErrType("unresolved " + name)
            }
            record(node, res.type, res.sym)
            return res.type
        }
        is PkgRecv -> {
            err(sel.start, "unresolved reference '" + name + "'")
            return ErrType("pkg")
        }
    }
}

fun BodyChecker.dotExpr(e: DotExpr, expected: Type?): Type {
    val sel = e.selector
    val recv = resolveReceiver(e.receiver, if (sel is NameExpr) sel.name else null)
    if (recv == null) {
        // still check call arguments so that nested expressions get their records
        if (sel is CallExpr) skipCall(sel)
        return ErrType("unresolved receiver")
    }
    var info: RecvInfo = recv
    if (recv is ValueRecv) info = ValueRecv(recv.type, recv.expr, e.safe)
    if (sel is NameExpr) {
        // a nested class / object / entry reached by name
        if (info is ClassRecv) {
            val nc = sema.nestedClassifier(info.cls, sel.name, 0)
            if (nc is ClassSym) {
                val ct = classQualifierType(nc)
                record(e, ct, null)
                return ct
            }
        }
        if (info is PkgRecv) {
            val c = sema.packageInfo(info.name).classes[sel.name]
            if (c != null) {
                val ct = classQualifierType(c)
                record(e, ct, null)
                return ct
            }
        }
        val t = selectProperty(e, info, sel, e.safe)
        return t ?: ErrType("property")
    }
    if (sel is CallExpr) {
        return callExpr(sel, info, expected, e)
    }
    return unsupported(e.start, "selector " + sel::class.simpleName)
}

fun BodyChecker.skipCall(c: CallExpr) {
    val a = c.args
    if (a != null) for (x in a) if (!isPostponedExpr(x.value)) expr(x.value, null)
}

// ---- calls ------------------------------------------------------------------------------------------------------------------------------

fun BodyChecker.makeArgs(e: CallExpr): ArrayList<Arg> {
    val args = ArrayList<Arg>()
    val a = e.args
    if (a != null) for (x in a) args.add(Arg(x.value, x.name, x.spread))
    for (l in e.lambdas) {
        val g = Arg(l, null, false)
        g.trailing = true
        args.add(g)
    }
    return args
}

fun BodyChecker.callExpr(e: CallExpr, recv: RecvInfo?, expected: Type?, node: Ast = e): Type {
    val callee = e.callee
    val args = makeArgs(e)
    var typeArgs: ArrayList<Type>? = null
    if (e.typeArgs.isNotEmpty()) {
        typeArgs = ArrayList()
        for (ta in e.typeArgs) {
            val t = ta.type
            typeArgs.add(if (t != null) sema.resolveType(t, scope) else sema.nullableAny)
        }
    }
    val groups: List<List<Candidate>>
    var safe = false
    var name: String? = null
    if (callee is NameExpr) {
        name = callee.name
        if (recv == null) {
            groups = unqualifiedGroups(callee.name)
        } else {
            when (recv) {
                is ValueRecv -> {
                    safe = recv.safe
                    var rt = recv.type
                    if (rt is ErrType) {
                        skipCall(e)
                        return rt
                    }
                    if (safe) rt = ops.makeNotNull(rt)
                    val g = ArrayList<List<Candidate>>()
                    g.addAll(memberAndExtensionGroups(rt, callee.name, false))
                    // property of function type: `recv.prop(args)`
                    val mp = memberPropRes(rt, callee.name)
                    if (mp != null) {
                        val ic = invokeCandidates(mp.type)
                        if (ic.isNotEmpty()) g.add(ic)
                    }
                    groups = g
                }
                is ClassRecv -> groups = classGroups(recv.cls, callee.name)
                is PkgRecv -> groups = pkgGroups(recv.name, callee.name)
            }
        }
    } else {
        // (expr)(args): invoke on the value
        val ct = fin(expr(callee, null))
        groups = listOf(invokeCandidates(ct))
    }
    var res = resolveCallGroups(node, groups, args, typeArgs, expected, false)
    if (res == null && recv is ValueRecv && name != null) {
        // `recv.f(args)` where `f` is a variable of type `Recv.(args) -> R`
        val vr = resolveSimpleName(name)
        val vt = vr?.type
        val ft = if (vt != null) (ops.asFunType(vt) ?: vt) else null
        if (ft is FunType && ft.receiver != null) {
            val a2 = ArrayList<Arg>()
            val ra = Arg(null, null, false)
            ra.type = if (safe) ops.makeNotNull(recv.type) else recv.type
            a2.add(ra)
            a2.addAll(args)
            res = resolveCallGroups(node, listOf(invokeCandidates(ft)), a2, typeArgs, expected, true)
        }
    }
    if (res == null) {
        err(e.start, "unresolved call '" + (name ?: "<expr>") + "'")
        return ErrType("unresolved call " + (name ?: ""))
    }
    var t = res.ret
    if (safe) t = t.withNullable(true)
    val rr = record(node, t, res.callee)
    rr.calleeText = res.calleeText
    for (i in args.indices) completeLiteralArg2(args[i], res.paramTypes, i)
    if (node !== e) record(e, t, res.callee)
    return t
}

fun BodyChecker.completeLiteralArg2(a: Arg, pts: List<Type?>, i: Int) {
    val e = a.expr ?: return
    if (i >= pts.size) return
    val pt = pts[i] ?: return
    val x = unwrapPostponed(e)
    val at = a.type
    if (at is IntLiteralType) {
        val r = rec.get(x) ?: return
        if (pt is ClassType) {
            val n = pt.cls.fqName()
            if (n == "kotlin.Long" || n == "kotlin.Short" || n == "kotlin.Byte" || n == "kotlin.Int") r.type = pt.withNullable(false)
        }
    }
}

fun BodyChecker.classGroups(c: ClassSym, name: String): List<List<Candidate>> {
    val groups = ArrayList<List<Candidate>>()
    sema.members.ensureSynth(c)
    // enum static functions, object members
    val st = ArrayList<Candidate>()
    val fs = c.funs[name]
    if (fs != null && (c.isEnum || c.isObject)) {
        for (f in fs) if (f.kind == FunKind.SYNTH && c.isEnum || c.isObject) st.add(Candidate(f, HashMap(), null))
    }
    if (c.isObject) {
        groups.clear()
        groups.addAll(memberAndExtensionGroups(c.thisType(), name, false))
        return groups
    }
    if (st.isNotEmpty()) groups.add(st)
    val nc = sema.nestedClassifier(c, name, 0)
    if (nc is ClassSym) groups.add(ctorCandidates(nc))
    val comp = c.companion
    if (comp != null) groups.addAll(memberAndExtensionGroups(comp.thisType(), name, false))
    return groups
}

fun BodyChecker.pkgGroups(pkg: String, name: String): List<List<Candidate>> {
    val groups = ArrayList<List<Candidate>>()
    val pi = sema.packages[pkg] ?: return groups
    val l = ArrayList<Candidate>()
    val fs = pi.funs[name]
    if (fs != null) for (f in fs) if (!f.isExtension) l.add(Candidate(f, HashMap(), null))
    val c = pi.classes[name]
    if (c != null) l.addAll(ctorCandidates(c))
    if (l.isNotEmpty()) groups.add(l)
    return groups
}
