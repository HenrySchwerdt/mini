package mini.lower

import mini.frontend.sema.*

/*
 * Local and anonymous classes of an inline function with reified type parameters.
 *
 * `inline fun <reified T> test(x: Any): Boolean { val o = object { val y = x is T }; return o.y }` must see a different T in every
 * expansion, but a local class is lowered once. So for every distinct list of concrete type arguments of such a function that is
 * called from the user files, the function is converted a second time (fresh IR, fresh local classes) into a clone that is added to
 * the file; the call is retargeted to the clone. The clone's classes form a [LocalGroup]: Ir2Mir converts their members with the type
 * arguments bound, and lets the local class symbols denote the clone's classes while it works on the clone (the class of a symbol is
 * looked up through [Ast2Ir.classMap], one class per symbol).
 */

/** The classes of one clone of an inline function and the reified type arguments they are lowered with. */
class LocalGroup(val subst: Map<TypeParamSym, Type>, val classes: Map<ClassSym, IrClass>)

private fun hasTypeParam(t: Type?): Boolean {
    if (t == null) return true
    return when (t) {
        is TypeParamType -> true
        is ClassType -> t.args.any { hasTypeParam(it.type) }
        is FunType -> (t.receiver != null && hasTypeParam(t.receiver)) || t.params.any { hasTypeParam(it) } || hasTypeParam(t.ret)
        is IsectType -> t.parts.any { hasTypeParam(it) }
        else -> false
    }
}

private fun collectClasses(e: IrElement, out: MutableList<IrClass>) {
    if (e is IrClass) out.add(e)
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) collectClasses(c, out)
}

/** converts everything below [e] now (bodies and initialisers are built on first access) */
private fun forceAll(e: IrElement) {
    val ch = ArrayList<IrElement>()
    irChildren(e, ch)
    for (c in ch) forceAll(c)
}

private fun under(d: IrDeclaration, roots: Set<IrDeclaration>): Boolean {
    var x: Any? = d
    var n = 0
    while (x is IrDeclaration && n < 64) {
        if (x in roots) return true
        x = x.parent
        n++
    }
    return false
}

private fun Ast2Ir.cloneFor(f: IrSimpleFunction, subst: Map<TypeParamSym, Type>): IrSimpleFunction? {
    val sym = f.sym ?: return null
    val oldClasses = ArrayList<IrClass>()
    collectClasses(f, oldClasses)
    val roots = HashSet<IrDeclaration>(oldClasses)
    roots.add(f)
    val savedClasses = HashMap<ClassSym, IrClass>()
    for (c in oldClasses) {
        val cs = c.sym
        if (cs != null) {
            val cur = classMap[cs]
            if (cur != null) savedClasses[cs] = cur
        }
    }
    val savedFuns = HashMap<FunSym, IrFunction>()
    for ((k, v) in funMap) if (under(v, roots)) savedFuns[k] = v
    val savedProps = HashMap<PropSym, IrProperty>()
    for ((k, v) in propMap) if (under(v, roots)) savedProps[k] = v
    for (k in savedClasses.keys) classMap.remove(k)
    for (k in savedFuns.keys) funMap.remove(k)
    for (k in savedProps.keys) propMap.remove(k)
    val firstNew = allClasses.size
    val fresh = HashMap<ClassSym, IrClass>()
    val nf = irFunction(sym)
    try {
        nf.parent = f.parent
        forceAll(nf)
        val newClasses = ArrayList<IrClass>()
        collectClasses(nf, newClasses)
        for (c in newClasses) {
            val cs = c.sym
            if (cs != null) fresh[cs] = c
        }
        var i = firstNew
        while (i < allClasses.size) {
            ensureFakeOverrides(allClasses[i])
            i++
        }
        forceAll(nf)
        val group = LocalGroup(subst, fresh)
        for (c in newClasses) c.localGroup = group
        nf.localGroup = group
    } finally {
        // the original function keeps its classes as the default meaning of the symbols
        for ((k, v) in savedClasses) classMap[k] = v
        for ((k, v) in savedFuns) funMap[k] = v
        for ((k, v) in savedProps) propMap[k] = v
    }
    return nf
}

/** Retargets the calls of inline functions with reified type parameters and local classes to clones, one per list of type arguments. */
internal fun Ast2Ir.specializeReifiedLocalClasses() {
    val cands = HashSet<IrSimpleFunction>()
    for (file in module.files) {
        if (file.isLib) continue
        for (d in file.declarations) {
            if (d is IrSimpleFunction && d.isInline && !d.stub && d.hasBody && d.typeParameters.any { it.reified } && d.sym != null) {
                val l = ArrayList<IrClass>()
                collectClasses(d, l)
                if (l.isNotEmpty()) cands.add(d)
            }
        }
    }
    if (cands.isEmpty()) return
    val clones = HashMap<IrSimpleFunction, HashMap<String, IrSimpleFunction?>>()
    val added = ArrayList<Pair<IrFile, IrSimpleFunction>>()
    for (file in module.files) {
        if (file.isStdlib || file.isLib) continue
        val w = object : IrWalker() {
            override fun visit(e: IrElement) {
                if (e is IrCall && e.callee in cands) {
                    val callee = e.callee
                    val subst = HashMap<TypeParamSym, Type>()
                    var ok = true
                    val key = StringBuilder()
                    for ((i, tp) in callee.typeParameters.withIndex()) {
                        if (!tp.reified) continue
                        val a = e.typeArguments.getOrNull(i)
                        if (a == null || hasTypeParam(a)) { ok = false; break }
                        subst[tp] = a
                        key.append(renderType(a)).append(';')
                    }
                    if (ok) {
                        val k = key.toString()
                        var byKey = clones[callee]
                        if (byKey == null) {
                            byKey = HashMap()
                            clones[callee] = byKey
                        }
                        if (!byKey.containsKey(k)) {
                            val nf = cloneFor(callee, subst)
                            byKey[k] = nf
                            val pf = callee.parent as? IrFile
                            if (nf != null && pf != null) added.add(pf to nf)
                        }
                        val target = byKey[k]
                        if (target != null) e.callee = target
                    }
                }
                visitChildren(e)
            }
        }
        for (d in ArrayList(file.declarations)) w.visit(d)
    }
    for ((pf, nf) in added) pf.declarations.add(nf)
}
