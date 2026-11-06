package mini.lower

import mini.backend.*
import mini.frontend.sema.*

/*
 * Reading a non-null field before the constructor assigned it.
 *
 * `open class Base { init { show() }; open fun show() {} }` / `class D : Base() { val s = "x"; override fun show() { println(s.length) } }`:
 * the virtual call from the constructor of `Base` runs while `D.s` still holds the zero of the allocation. Kotlin/JVM reads `null` and
 * throws a NullPointerException at the use; a field read that continues as if the pointer were valid is a null dereference (or, for a
 * String, silently an empty string). The window exists only for a class whose own initialization, or the initialization of one of its
 * superclasses, lets `this` out (a member call with the implicit receiver, `this` passed or captured): such a class is [initLeaksThis]
 * and the reads of its non-null reference fields get a null check that throws the NullPointerException. Other classes pay nothing.
 */

private class ThisUse(val owners: Set<IrValueDeclaration>) : IrWalker() {
    var found = false

    private fun isThis(e: IrExpression?): Boolean = e is IrGetValue && e.owner in owners

    override fun visit(e: IrElement) {
        if (found) return
        if (e is IrGetValue) {
            if (e.owner in owners) found = true
            return
        }
        if (e is IrClass) return
        if (e is IrGetField && isThis(e.receiver)) return
        if (e is IrSetField && isThis(e.receiver)) {
            visit(e.value)
            return
        }
        visitChildren(e)
    }
}

/** True when the initialization of [c] itself (property initializers, `init` blocks, constructor bodies) uses `this` other than to read or write its own fields. */
private fun classInitUsesThis(c: IrClass): Boolean {
    val owners = HashSet<IrValueDeclaration>()
    val tr = c.thisReceiver
    if (tr != null) owners.add(tr)
    for (d in c.declarations) {
        if (d is IrConstructor) for (p in d.parameters) if (p.kind == IrParameterKind.DispatchReceiver) owners.add(p)
    }
    if (owners.isEmpty()) return false
    val w = ThisUse(owners)
    for (d in c.declarations) {
        when (d) {
            is IrAnonymousInitializer -> w.visit(d.body)
            is IrProperty -> { val f = d.backingField; if (f != null) w.visit(f) }
            is IrField -> w.visit(d)
            is IrConstructor -> { val b = d.body; if (b != null) w.visit(b) }
            else -> {}
        }
        if (w.found) return true
    }
    return false
}

/** [c] or one of its superclasses lets `this` out of its initialization. */
internal fun Ir2Mir.initLeaksThis(c: IrClass): Boolean {
    val cached = initLeakCache[c]
    if (cached != null) return cached
    initLeakCache[c] = false // (a cycle of supertypes is an error elsewhere; do not loop)
    var r = classInitUsesThis(c)
    if (!r) {
        for (t in c.superTypes) {
            val s = t.classOrNull ?: continue
            if (s !== c && s.kind != IrClassKind.INTERFACE && initLeaksThis(s)) { r = true; break }
        }
    }
    initLeakCache[c] = r
    return r
}

/**
 * The read of [f] ([v], a `FieldGet`): checked against null when the field can still be unset while code runs that reads it.
 * That is the case for a field of a class that lets `this` out of its initialization ([initLeaksThis]), and for a field of an
 * `object` or of a file (a top-level property) that is assigned by an initializer: two objects (or files) that read each other's
 * properties while they initialize, or a function called from an earlier initializer, see the zero of the allocation.
 */
internal fun Ir2Mir.guardUnsetField(v: MirExpr, f: IrField): MirExpr {
    if (v !is MirExpr.FieldGet) return v
    val t = v.type
    if (!(t === TString || t is TObject && !t.nullable)) return v
    val kt = f.type
    if (kt.isMarkedNullable() || !(kt is ClassType || kt is FunType)) return v
    if (f.correspondingProperty?.isLateinit == true) return v
    val parent = f.parent
    if (parent is IrFile) {
        val init = f.initializer?.expression ?: return v
        return if (init is IrConst) v else MirExpr.NotNull(v)
    }
    val owner = parent as? IrClass ?: return v
    if (owner !in classInfos) return v
    if (owner.kind == IrClassKind.OBJECT) {
        val init = f.initializer?.expression
        if (init != null && init !is IrConst) return MirExpr.NotNull(v)
    }
    return if (initLeaksThis(owner)) MirExpr.NotNull(v) else v
}
