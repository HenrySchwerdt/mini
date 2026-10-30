package mini.frontend.sema

import mini.frontend.*

/*
 * Declaration level checking: classes (supertype constructor calls, members, init blocks, enum entries), functions,
 * properties (initializers, accessors, delegates), implicit types with cycle detection.
 */

fun Sema.returnTypeOf(f: FunSym): Type {
    ensureSig(f)
    val d = f.decl
    val needs = d != null && d.returnType == null && d.expressionBody && f.kind != FunKind.ANONYMOUS
    if (!needs) return f.returnType ?: ErrType("no return type")
    if (f.retState == 2) return f.returnType ?: ErrType("no return type")
    if (f.retState == 1) {
        error(f.file, f.start, "type checking has run into a recursive problem (" + f.name + ")")
        return ErrType("recursive " + f.name)
    }
    checkFun(f)
    return f.returnType ?: ErrType("no return type")
}

fun Sema.checkFun(f: FunSym) {
    if (f.bodyChecked) return
    f.bodyChecked = true
    val file = f.file ?: return
    val d = f.decl ?: return
    ensureSig(f)
    val needs = d.returnType == null && d.expressionBody
    if (needs) f.retState = 1
    val bc = BodyChecker(this, file)
    for (tp in f.typeParams) ensureBounds(tp)
    bc.checkAnnotations(d.mods)
    for (p in d.params) bc.checkAnnotations(p.mods)
    recordFunSignature(bc, f)
    bc.checkFunBody(f)
    if (needs) {
        f.retState = 2
        if (f.returnType == null) f.returnType = ErrType("no return type")
    }
    val rt = f.returnType
    if (rt != null) bc.record(d, rt, f)
}

fun Sema.recordFunSignature(bc: BodyChecker, f: FunSym) {
    val d = f.decl ?: return
    var i = 0
    for (p in d.params) {
        val v = f.params[i]
        bc.record(p, v.type ?: ErrType("param"), v)
        i++
    }
}

fun Sema.typeOfProp(p: PropSym): Type {
    ensurePropSig(p)
    val t = p.type
    if (t != null && p.typeState == 2) return t
    if (p.typeState == 1) {
        error(p.file, p.start, "type checking has run into a recursive problem (" + p.name + ")")
        return ErrType("recursive " + p.name)
    }
    checkProp(p)
    return p.type ?: ErrType("no property type")
}

/** scope for property initializers and init blocks of a class: primary constructor parameters over the body scope */
fun Sema.initializerScope(c: ClassSym, allParams: Boolean = false): Scope {
    val body = c.bodyScope!!
    val pc = c.primaryCtor ?: return body
    ensureSig(pc)
    val l = LocalScope(body)
    for (p in pc.params) if (allParams || p.decl?.valVar == null) l.vars[p.name] = p
    return l
}

fun Sema.checkProp(p: PropSym) {
    if (p.initChecked) return
    p.initChecked = true
    val d = p.decl
    val file = p.file ?: return
    if (d == null) {
        // constructor property
        val cp = p.ctorParam
        if (cp != null) {
            ensurePropSig(p)
            val bc = BodyChecker(this, file)
            bc.record(cp, p.type ?: ErrType("?"), p)
        }
        return
    }
    ensurePropSig(p)
    BodyChecker(this, file).checkAnnotations(d.mods)
    val needsInference = p.type == null
    if (needsInference) p.typeState = 1
    val bc = BodyChecker(this, file)
    val o = p.owner
    var base: Scope = if (o != null) initializerScope(o) else p.scope
    var accBase: Scope = if (o != null) o.bodyScope!! else p.scope
    base = TypeParamScope(p.typeParams, base)
    accBase = TypeParamScope(p.typeParams, accBase)
    val recv = p.receiver
    if (recv != null) {
        val rs1 = ReceiverScope(recv, p.name, base)
        rs1.prop = p
        base = rs1
        val rs2 = ReceiverScope(recv, p.name, accBase)
        rs2.prop = p
        accBase = rs2
    }
    bc.scope = base
    val declared = if (!needsInference) p.type else null
    val init = d.initializer
    val deleg = d.delegate
    if (init != null) {
        val t = bc.expr(init, declared)
        if (needsInference) p.type = bc.fin(t) else bc.expectCompatible(init, t, declared)
    } else if (deleg != null) {
        val dt = bc.expr(deleg, null)
        if (needsInference) p.type = bc.delegatedType(bc.fin(dt), d.start)
    }
    // accessors
    val g = d.getter
    if (g != null && g.body != null) {
        val gc = BodyChecker(this, file)
        val ls = LocalScope(accBase)
        val gt = if (g.returnType != null) resolveType(g.returnType, accBase) else p.type
        val fv = VarSym("field", gt ?: ErrType("field"), true, false, g.start)
        fv.isField = true
        ls.vars["field"] = fv
        gc.scope = ls
        val target = ReturnTarget(null, gt, null, false)
        gc.targets.add(target)
        val body = g.body
        val t: Type
        if (g.expressionBody) {
            t = gc.expr(body, gt)
        } else {
            gc.blockBody(body, gt)
            t = ErrType("block getter")
        }
        bc.record(g, gt ?: (if (g.expressionBody) gc.fin(t) else ErrType("getter")), null)
        if (needsInference && p.type == null) {
            if (g.expressionBody) {
                val all = ArrayList<Type>()
                all.add(gc.fin(t))
                all.addAll(target.returned)
                p.type = ops.commonSuperType(all)
            } else {
                p.type = if (target.returned.isNotEmpty()) ops.commonSuperType(target.returned) else unitType
            }
        }
    }
    if (p.type == null) p.type = ErrType("untyped property " + p.name)
    p.typeState = 2
    val s = d.setter
    if (s != null && s.body != null) {
        val sc = BodyChecker(this, file)
        val ls = LocalScope(accBase)
        val fv2 = VarSym("field", p.type ?: ErrType("field"), true, false, s.start)
        fv2.isField = true
        ls.vars["field"] = fv2
        val pn = s.param?.name ?: "value"
        val sv = VarSym(pn, p.type ?: ErrType("value"), false, true, s.param?.start ?: s.start)
        ls.vars[pn] = sv
        val sp = s.param
        if (sp != null) bc.record(sp, p.type ?: ErrType("value"), sv)
        sc.scope = ls
        sc.targets.add(ReturnTarget(null, unitType, null, false))
        sc.blockBody(s.body, null)
        bc.record(s, unitType, null)
    }
    val pr = bc.record(d, p.type ?: ErrType("?"), p)
    if (deleg != null) {
        val dtp = records.get(deleg)?.type
        if (dtp != null) pr.extra = bc.delegateInfo(bc.fin(dtp), p.isVar, p.type ?: nullableAny, d.start)
    }
}

fun Sema.checkClass(c: ClassSym) {
    if (c.checked) return
    c.checked = true
    val file = c.file ?: return
    val d = c.decl ?: return
    ensureSupertypes(c)
    members.ensureSynth(c)
    for (tp in c.typeParams) ensureBounds(tp)
    val hs = c.headerScope ?: return
    val bodyScope = c.bodyScope ?: return
    BodyChecker(this, file).checkAnnotations(d.mods)
    for (en in d.enumEntries) BodyChecker(this, file).checkAnnotations(en.mods)
    // primary constructor parameters and super constructor calls
    val pc = c.primaryCtor
    val bc = BodyChecker(this, file)
    val pcd = d.primaryCtor
    if (pcd != null) bc.record(pcd, c.thisType(), pc)
    if (pc != null) {
        ensureSig(pc)
        var i = 0
        for (p in pc.primaryParams) {
            val v = pc.params[i]
            bc.record(p, v.type ?: ErrType("?"), v)
            i++
        }
        bc.scope = hs
        for (p in pc.primaryParams) bc.checkAnnotations(p.mods)
        for (p in pc.primaryParams) {
            val dv = p.default
            if (dv != null) {
                val pt = pc.params[pc.primaryParams.indexOf(p)].let { it.varargElement ?: it.type }
                bc.expr(dv, pt)
            }
        }
    }
    val initScope = initializerScope(c)
    for (s in d.supers) {
        if (s is SuperCall) {
            val sc = BodyChecker(this, file)
            sc.scope = initializerScope(c, true)
            sc.superConstructorCall(c, s)
        }
    }
    for (en in d.enumEntries) {
        val ec = BodyChecker(this, file)
        ec.scope = bodyScope
        ec.enumEntryCall(c, en)
        val body = enumEntryBodies[en]
        if (body != null) checkClass(body)
    }
    for (m in d.members) {
        when (m) {
            is FunDecl -> {
                val f = declSyms[m] as? FunSym
                if (f != null) checkFun(f)
            }
            is PropertyDecl -> {
                val p = declSyms[m] as? PropSym
                if (p != null) checkProp(p)
            }
            is ClassDecl -> {
                val nc = declSyms[m] as? ClassSym
                if (nc != null) checkClass(nc)
            }
            is InitDecl -> {
                val b = m.body
                if (b != null) {
                    val ic = BodyChecker(this, file)
                    ic.scope = initScope
                    ic.targets.add(ReturnTarget(null, unitType, null, false))
                    val names = HashSet<String>()
                    AstWalk.capturedAssigned(b, names)
                    ic.capturedNames = names
                    ic.blockNode(b, null, true)
                }
            }
            is SecondaryCtor -> {
                val f = declSyms[m] as? FunSym
                if (f != null) checkSecondary(c, f, m)
            }
            else -> {}
        }
    }
    // constructor properties are recorded once
    if (pc != null) {
        for ((_, ps) in c.props) for (p in ps) if (p.decl == null) checkProp(p)
    }
}

fun Sema.checkSecondary(c: ClassSym, f: FunSym, m: SecondaryCtor) {
    val file = f.file ?: return
    ensureSig(f)
    val bc = BodyChecker(this, file)
    bc.record(m, c.thisType(), f)
    val l = LocalScope(c.bodyScope)
    var i = 0
    for (p in m.params) {
        val v = f.params[i]
        l.vars[p.name] = v
        bc.record(p, v.type ?: ErrType("?"), v)
        i++
    }
    bc.scope = l
    i = 0
    for (p in m.params) {
        val dv = p.default
        if (dv != null) bc.expr(dv, f.params[i].varargElement ?: f.params[i].type)
        i++
    }
    val del = m.delegation
    if (del != null) bc.delegationCall(c, del)
    val b = m.body
    if (b != null) {
        bc.targets.add(ReturnTarget(null, unitType, null, false))
        val names = HashSet<String>()
        AstWalk.capturedAssigned(b, names)
        bc.capturedNames = names
        bc.blockNode(b, null, true)
    }
}

// ---- constructor calls ---------------------------------------------------------------------------------------------------------------------

fun BodyChecker.superConstructorCall(c: ClassSym, s: SuperCall) {
    val st = sema.resolveType(s.type, c.headerScope ?: scope)
    if (st !is ClassType) return
    val args = ArrayList<Arg>()
    for (a in s.args) args.add(Arg(a.value, a.name, a.spread))
    val cands = ArrayList<Candidate>()
    val sub = ops.substOf(st)
    for (k in st.cls.ctors) cands.add(Candidate(k, sub, null))
    // the super class' type parameters are already instantiated
    val res = resolveCallGroups(s, listOf(cands), args, null, null, false)
    val rr = record(s, sema.unitType, res?.callee)
    rr.call = res?.info
    if (res == null) err(s.start, "no matching super constructor")
}

fun BodyChecker.delegationCall(c: ClassSym, d: CtorDelegation) {
    val args = ArrayList<Arg>()
    for (a in d.args) args.add(Arg(a.value, a.name, a.spread))
    val cands = ArrayList<Candidate>()
    if (d.isThis) {
        for (k in c.ctors) cands.add(Candidate(k, HashMap(), null))
        val self = ArrayList<Type>()
        val m = HashMap<TypeParamSym, Type>()
        for (tp in c.typeParams) m[tp] = TypeParamType(tp, false, false)
        for (k in c.ctors) cands.add(Candidate(k, m, null))
        cands.clear()
        for (k in c.ctors) cands.add(Candidate(k, m, null))
    } else {
        val sc = sema.superclassOf(c)
        if (sc != null) {
            val sub = ops.substOf(sc)
            for (k in sc.cls.ctors) cands.add(Candidate(k, sub, null))
        }
    }
    val res = resolveCallGroups(d, listOf(cands), args, null, null, false)
    val text = file.text
    var kw = text.indexOf(if (d.isThis) "this" else "super", d.start)
    if (kw < 0 || kw > d.end) kw = d.start
    val dr = recordAt(kw, d.end, sema.unitType, res?.callee, res?.calleeText)
    dr.call = res?.info
    rec.map[d] = dr
}

fun BodyChecker.enumEntryCall(c: ClassSym, en: EnumEntryDecl) {
    val args = ArrayList<Arg>()
    val a = en.args
    if (a != null) for (x in a) args.add(Arg(x.value, x.name, x.spread))
    val cands = ArrayList<Candidate>()
    for (k in c.ctors) cands.add(Candidate(k, HashMap(), null))
    val res = resolveCallGroups(en, listOf(cands), args, null, null, false)
    val enr = record(en, c.thisType(), res?.callee)
    enr.call = res?.info
    val text = file.text
    var from = text.indexOf(en.name, en.start)
    if (from >= 0) {
        from += en.name.length
        while (from < en.end && text[from].isWhitespace()) from++
        if (from < en.end && text[from] == '(') {
            var depth = 0
            var j = from
            while (j < en.end) {
                val ch = text[j]
                if (ch == '(') depth++
                else if (ch == ')') {
                    depth--
                    if (depth == 0) break
                } else if (ch == '"') {
                    j++
                    while (j < en.end && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                }
                j++
            }
            recordAt(from, j + 1, sema.unitType, res?.callee, res?.calleeText)
        }
    }
}

// ---- driver -------------------------------------------------------------------------------------------------------------------------------------

/** checks every declaration of every file */
fun Sema.analyze() {
    for (f in files) {
        BodyChecker(this, f).checkFileDecls()
    }
}

fun Sema.dumpFile(f: FileInfo): List<Rec> {
    val out = ArrayList<Rec>()
    val nodes = fileNodes[f.index] ?: return out
    val rn = Rendering(this)
    for (n in nodes) {
        val r = records.get(n) ?: continue
        var end = n.end
        if (n is PropertyDecl || n is DestructuringDecl) {
            // the PSI range of a declaration statement does not include its terminating semicolon or a trailing comment
            var last = n.start
            if (n is PropertyDecl) {
                val ex = ArrayList<Ast>()
                val i = n.initializer
                if (i != null) ex.add(i)
                val dl = n.delegate
                if (dl != null) ex.add(dl)
                val ty = n.type
                if (ty != null) ex.add(ty)
                val st = n.setter
                if (st != null) ex.add(st)
                val gt = n.getter
                if (gt != null) ex.add(gt)
                for (x in ex) if (x.end > last) last = x.end
                if (last > n.start && last < end) end = last
            } else if (n is DestructuringDecl) {
                val i = n.initializer
                if (i != null && i.end < end) end = i.end
            }
            while (end > n.start && (f.text[end - 1] == ';' || f.text[end - 1].isWhitespace())) end--
        }
        out.add(Rec(n.start, end, n::class.simpleName ?: "?", renderType(r.type), renderCallee(this, r.callee, r.calleeText)))
        val sm = r.smart
        if (sm != null) out.add(Rec(n.start, end, "SmartCast", renderType(sm), null))
    }
    return out
}

fun Sema.dumpText(f: FileInfo): String {
    val sb = StringBuilder()
    for (r in dumpFile(f)) {
        sb.append(r.start).append('-').append(r.end).append(' ').append(r.kind).append(" :: ").append(r.type)
        if (r.callee != null) sb.append(" => ").append(r.callee)
        sb.append('\n')
    }
    return sb.toString()
}
