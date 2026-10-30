package mini.lower

import mini.frontend.sema.*

/*
 * Fake overrides: the members a class inherits and does not declare get a function (property) of their own in
 * `IrClass.declarations`, with the signature seen from the class (type arguments of the supertypes substituted) and
 * `overridden` pointing at the inherited members. Declared members that override get their `overridden` list as well.
 * This is what K2's IrFakeOverrideBuilder does; Ir2Mir needs it for vtable / itable construction and dispatch.
 */

private val placeholders = ArrayList<TypeParamSym>()

private fun placeholder(i: Int): TypeParamSym {
    while (placeholders.size <= i) placeholders.add(TypeParamSym("#" + placeholders.size, Variance.INV, false, null, placeholders.size))
    return placeholders[i]
}

/** The signature key of a function seen from a class: name, receiver and parameter types after substitution. */
private fun Ast2Ir.sigKey(f: IrSimpleFunction, m: Map<TypeParamSym, Type>): String {
    val mm = HashMap<TypeParamSym, Type>()
    mm.putAll(m)
    var i = 0
    for (tp in f.typeParameters) {
        mm[tp] = TypeParamType(placeholder(i), false, false)
        i++
    }
    val sb = StringBuilder(f.name)
    sb.append('(')
    for (p in f.parameters) {
        if (p.kind == IrParameterKind.DispatchReceiver) continue
        if (p.kind == IrParameterKind.ExtensionReceiver) sb.append("ext:")
        sb.append(renderType(ops.substitute(p.type, mm)))
        sb.append(',')
    }
    sb.append(')')
    return sb.toString()
}

private fun Ast2Ir.propKey(p: IrProperty, m: Map<TypeParamSym, Type>): String {
    val g = p.getter
    val sb = StringBuilder(p.name)
    if (g != null) {
        for (q in g.parameters) {
            if (q.kind == IrParameterKind.ExtensionReceiver) sb.append(":").append(renderType(ops.substitute(q.type, m)))
        }
    }
    return sb.toString()
}

/** all functions [f] overrides, transitively (including itself) */
fun allOverriddenOf(f: IrSimpleFunction, acc: MutableSet<IrSimpleFunction>) {
    if (acc.add(f)) for (o in f.overridden) allOverriddenOf(o, acc)
}

private fun minimalFuns(group: List<IrSimpleFunction>): List<IrSimpleFunction> {
    val out = ArrayList<IrSimpleFunction>()
    for (a in group) {
        var covered = false
        for (b in group) {
            if (a === b) continue
            val s = HashSet<IrSimpleFunction>()
            allOverriddenOf(b, s)
            if (s.contains(a)) covered = true
        }
        if (!covered && !out.contains(a)) out.add(a)
    }
    return out
}

private fun minimalProps(group: List<IrProperty>): List<IrProperty> {
    val out = ArrayList<IrProperty>()
    for (a in group) {
        var covered = false
        for (b in group) {
            if (a === b) continue
            if (propOverrides(b, a)) covered = true
        }
        if (!covered && !out.contains(a)) out.add(a)
    }
    return out
}

private fun propOverrides(b: IrProperty, a: IrProperty): Boolean {
    for (o in b.overridden) {
        if (o === a || propOverrides(o, a)) return true
    }
    return false
}

private class InhFun(val fn: IrSimpleFunction, val site: ClassType, val subst: Map<TypeParamSym, Type>)

private class InhProp(val prop: IrProperty, val site: ClassType, val subst: Map<TypeParamSym, Type>)

fun Ast2Ir.buildFakeOverrides(c: IrClass, cs: ClassSym) {
    val funGroups = LinkedHashMap<String, ArrayList<InhFun>>()
    val propGroups = LinkedHashMap<String, ArrayList<InhProp>>()
    for (st in c.superTypes) {
        if (st !is ClassType) continue
        val sir = irClass(st.cls)
        ensureFakeOverrides(sir)
        val m = ops.substOf(st)
        for (d in sir.declarations) {
            if (d is IrSimpleFunction) {
                if (d.visibility == "private") continue
                val k = sigKey(d, m)
                var l = funGroups[k]
                if (l == null) {
                    l = ArrayList()
                    funGroups[k] = l
                }
                l.add(InhFun(d, st, m))
            } else if (d is IrProperty) {
                val g = d.getter
                if (g != null && g.visibility == "private") continue
                val k = propKey(d, m)
                var l = propGroups[k]
                if (l == null) {
                    l = ArrayList()
                    propGroups[k] = l
                }
                l.add(InhProp(d, st, m))
            }
        }
    }
    val empty = HashMap<TypeParamSym, Type>()
    // declared members (their `overridden` lists)
    val declaredFuns = HashMap<String, IrSimpleFunction>()
    val declaredProps = HashMap<String, IrProperty>()
    for (d in c.declarations) {
        if (d is IrSimpleFunction && !d.isFakeOverride) declaredFuns[sigKey(d, empty)] = d
        else if (d is IrProperty && !d.isFakeOverride) declaredProps[propKey(d, empty)] = d
    }
    val fakes = ArrayList<IrDeclaration>()
    // properties first (sorted by name), then functions (sorted by name), as K2 lists them
    val propKeys = ArrayList<String>(propGroups.keys)
    propKeys.sort()
    for (k in propKeys) {
        val group = propGroups[k]!!
        val props = ArrayList<IrProperty>()
        for (g in group) props.add(g.prop)
        val minimal = minimalProps(props)
        val declared = declaredProps[k]
        if (declared != null) {
            declared.overridden = minimal
            val gs = ArrayList<IrSimpleFunction>()
            val ss = ArrayList<IrSimpleFunction>()
            for (p in minimal) {
                val pg = p.getter
                if (pg != null) gs.add(pg)
                val ps = p.setter
                if (ps != null) ss.add(ps)
            }
            val dg = declared.getter
            if (dg != null) dg.overridden = gs
            val ds = declared.setter
            if (ds != null) ds.overridden = ss
            continue
        }
        val first = group[0]
        val fp = makeFakeProperty(c, group, minimal)
        fakes.add(fp)
    }
    val funKeys = ArrayList<String>(funGroups.keys)
    funKeys.sort()
    for (k in funKeys) {
        val group = funGroups[k]!!
        val fns = ArrayList<IrSimpleFunction>()
        for (g in group) fns.add(g.fn)
        val minimal = minimalFuns(fns)
        val declared = declaredFuns[k]
        if (declared != null) {
            declared.overridden = minimal
            continue
        }
        // the most specific result type among the inherited members wins (K2 intersects them)
        var best = group[0]
        var bt = ops.substitute(best.fn.returnType, best.subst)
        for (g in group) {
            val gt = ops.substitute(g.fn.returnType, g.subst)
            if (!typeEquals(gt, bt) && ops.isSubtype(gt, bt)) {
                best = g
                bt = gt
            }
        }
        fakes.add(makeFakeFunction(c, best.site, best.subst, best.fn, minimal, null))
    }
    // functions that are accessors of declared properties are not in `declarations`; nothing else to do
    for (f in fakes) c.declarations.add(f)
}

private fun fakeModality(base: List<IrSimpleFunction>): IrModality {
    for (b in base) if (b.modality != IrModality.ABSTRACT) return b.modality
    return IrModality.ABSTRACT
}

private fun Ast2Ir.makeFakeFunction(
    c: IrClass,
    site: ClassType,
    subst: Map<TypeParamSym, Type>,
    first: IrSimpleFunction,
    minimal: List<IrSimpleFunction>,
    prop: IrProperty?,
): IrSimpleFunction {
    val f = IrSimpleFunction(first.name)
    f.isFakeOverride = true
    f.origin = "FAKE_OVERRIDE"
    f.parent = c
    f.visibility = first.visibility
    f.modality = fakeModality(minimal)
    f.isInline = first.isInline
    f.isExternal = first.isExternal
    f.isOperator = first.isOperator
    f.sym = first.sym
    f.cSymbolName = first.cSymbolName
    f.typeParameters = first.typeParameters
    f.overridden = minimal
    f.correspondingProperty = prop
    for (p in first.parameters) {
        val t = if (p.kind == IrParameterKind.DispatchReceiver) site else irType(ops.substitute(p.type, subst))
        val np = IrValueParameter(p.name, t, p.kind, p.index)
        np.parent = f
        np.isNoinline = p.isNoinline
        np.isCrossinline = p.isCrossinline
        val ve = p.varargElementType
        if (ve != null) np.varargElementType = irType(ops.substitute(ve, subst))
        f.parameters.add(np)
    }
    f.returnType = irType(ops.substitute(first.returnType, subst))
    return f
}

private fun Ast2Ir.makeFakeProperty(c: IrClass, group: List<InhProp>, minimal: List<IrProperty>): IrProperty {
    val first = group[0]
    val p = IrProperty(first.prop.name, first.prop.sym)
    p.isFakeOverride = true
    p.origin = "FAKE_OVERRIDE"
    p.parent = c
    p.isVar = first.prop.isVar
    p.isLateinit = first.prop.isLateinit
    p.overridden = minimal
    p.cSymbolName = first.prop.cSymbolName
    val getters = ArrayList<IrSimpleFunction>()
    val setters = ArrayList<IrSimpleFunction>()
    for (q in minimal) {
        val g = q.getter
        if (g != null) getters.add(g)
        val s = q.setter
        if (s != null) setters.add(s)
    }
    val fg = first.prop.getter
    if (fg != null) p.getter = makeFakeFunction(c, first.site, first.subst, fg, getters, p)
    val fs = first.prop.setter
    if (fs != null) p.setter = makeFakeFunction(c, first.site, first.subst, fs, setters, p)
    return p
}

/**
 * The member of [c] (declared or fake override) that stands for [f], a function declared in [c] or in one of its
 * supertypes: what a call through a receiver of static class [c] refers to in K2's IR.
 */
/** whether [f] is declared in [c] or one of its (fake) members stands for it */
fun memberFound(c: IrClass, f: IrSimpleFunction): Boolean {
    if (f.parent === c) return true
    for (d in c.declarations) {
        if (d is IrSimpleFunction) {
            val s = HashSet<IrSimpleFunction>()
            allOverriddenOf(d, s)
            if (s.contains(f)) return true
        } else if (d is IrProperty) {
            val g = d.getter
            if (g != null) {
                val s = HashSet<IrSimpleFunction>()
                allOverriddenOf(g, s)
                if (s.contains(f)) return true
            }
            val st = d.setter
            if (st != null) {
                val s = HashSet<IrSimpleFunction>()
                allOverriddenOf(st, s)
                if (s.contains(f)) return true
            }
        }
    }
    return false
}

fun findMemberIn(c: IrClass, f: IrSimpleFunction): IrSimpleFunction {
    val own = f.parent
    if (own === c) return f
    for (d in c.declarations) {
        if (d is IrSimpleFunction) {
            if (d === f) return d
            val s = HashSet<IrSimpleFunction>()
            allOverriddenOf(d, s)
            if (s.contains(f)) return d
        } else if (d is IrProperty) {
            val g = d.getter
            if (g != null) {
                val s = HashSet<IrSimpleFunction>()
                allOverriddenOf(g, s)
                if (s.contains(f)) return g
            }
            val st = d.setter
            if (st != null) {
                val s = HashSet<IrSimpleFunction>()
                allOverriddenOf(st, s)
                if (s.contains(f)) return st
            }
        }
    }
    return f
}
