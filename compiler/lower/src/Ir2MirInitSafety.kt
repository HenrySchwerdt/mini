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

/** The default accessor of a property with a backing field: it only reads or writes that field (a final one cannot be overridden by code that runs in the subclass). */
private fun isFieldAccessor(f: IrSimpleFunction): Boolean {
    val prop = f.correspondingProperty ?: return false
    return f.origin == "DEFAULT_PROPERTY_ACCESSOR" && f.modality == IrModality.FINAL && !f.isFakeOverride && prop.backingField != null && !prop.isLateinit
}

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
        if (e is IrCall && isThis(e.arguments.firstOrNull()) && isFieldAccessor(e.callee)) {
            for (i in 1 until e.arguments.size) { val a = e.arguments[i]; if (a != null) visit(a) }
            return
        }
        if (e is IrGetField && isThis(e.receiver)) return
        if (e is IrSetField && isThis(e.receiver)) {
            visit(e.value)
            return
        }
        visitChildren(e)
    }
}

/**
 * Where the initialization of [c] itself (property initializers, `init` blocks, constructor bodies) first uses `this` other than to read or
 * write its own fields: the index in `c.declarations` of the property / initializer, `declarations.size` when only a constructor body does
 * (those run after every initializer), -1 when none does.
 */
private fun classInitLeakIndex(c: IrClass): Int {
    val owners = HashSet<IrValueDeclaration>()
    val tr = c.thisReceiver
    if (tr != null) owners.add(tr)
    for (d in c.declarations) {
        if (d is IrConstructor) for (p in d.parameters) if (p.kind == IrParameterKind.DispatchReceiver) owners.add(p)
    }
    if (owners.isEmpty()) return -1
    for ((i, d) in c.declarations.withIndex()) {
        val w = ThisUse(owners)
        when (d) {
            is IrAnonymousInitializer -> w.visit(d.body)
            is IrProperty -> { val f = d.backingField; if (f != null) w.visit(f) }
            is IrField -> w.visit(d)
            else -> {}
        }
        if (w.found) return i
    }
    for (d in c.declarations) {
        if (d !is IrConstructor) continue
        val b = d.body ?: continue
        val w = ThisUse(owners)
        w.visit(b)
        if (w.found) return c.declarations.size
    }
    return -1
}

private fun Ir2Mir.initLeakIndex(c: IrClass): Int = initLeakAt.getOrPut(c) { classInitLeakIndex(c) }

/** The index in `c.declarations` of the declaration that assigns [f] by an initializer, or -1 (assigned elsewhere: an `init` block, a constructor). */
private fun initializedAt(c: IrClass, f: IrField): Int {
    for ((i, d) in c.declarations.withIndex()) {
        val b = if (d is IrProperty) d.backingField else if (d is IrField) d else null
        if (b === f) return if (f.initializer?.expression != null) i else -1
    }
    return -1
}

/** [c] or one of its superclasses lets `this` out of its initialization. */
internal fun Ir2Mir.initLeaksThis(c: IrClass): Boolean {
    val cached = initLeakCache[c]
    if (cached != null) return cached
    initLeakCache[c] = false // (a cycle of supertypes is an error elsewhere; do not loop)
    var r = initLeakIndex(c) >= 0
    if (!r) {
        for (t in c.superTypes) {
            val s = t.classOrNull ?: continue
            if (s !== c && s.kind != IrClassKind.INTERFACE && initLeaksThis(s)) { r = true; break }
        }
    }
    initLeakCache[c] = r
    return r
}

private fun isStdlibDecl(d: IrDeclaration): Boolean = Reachability.fileOf(d)?.isStdlib == true

/**
 * An expression that cannot run user code or read a property of the program: constants, strings built from them and calls of stdlib
 * functions / constructors on such values (`IntArray(16)`, `ArrayList<Node>()`, `"a" + 1`, `listOf("x")`). A stdlib function that only gets
 * such values has no way to reach user code.
 */
private fun isSimpleInit(e: IrElement?): Boolean {
    when (e) {
        null -> return true
        is IrConst -> return true
        is IrStringConcatenation -> return e.arguments.all { isSimpleInit(it) }
        is IrTypeOperatorCall -> return isSimpleInit(e.argument)
        is IrVararg -> return e.elements.all { isSimpleInit(if (it is IrSpreadElement) it.expression else it as IrElement) }
        is IrGetObjectValue -> return isStdlibDecl(e.classOwner)
        is IrCall -> return e.superQualifier == null && isStdlibDecl(e.callee) && e.arguments.all { isSimpleInit(it) }
        is IrConstructorCall -> return isStdlibDecl(e.callee) && e.arguments.all { isSimpleInit(it) }
        else -> return false
    }
}

/**
 * The index in `declarations` of the first initializer of a file (its top-level property initializers) or of an `object` that can run user
 * code or read another property (Int.MAX_VALUE when none does; 0 when the superclass of the object may). A property declared before that
 * point is assigned before any such code runs, and the object / file is initialized as a whole on first use: nothing can see it unset.
 */
private fun Ir2Mir.firstNonSimpleInit(parent: IrDeclarationParent): Int {
    val cached = simpleInitCache[parent]
    if (cached != null) return cached
    simpleInitCache[parent] = 0
    val decls: List<IrDeclaration> = if (parent is IrFile) parent.declarations else if (parent is IrClass) parent.declarations else emptyList()
    var r = Int.MAX_VALUE
    if (parent is IrClass) {
        for (t in parent.superTypes) {
            val sc = t.classOrNull ?: continue
            if (sc.kind != IrClassKind.INTERFACE && !(sc.name == "Any" && isStdlibDecl(sc))) r = 0
        }
        for (d in decls) {
            if (d !is IrConstructor) continue
            val b = d.body
            if (b != null && (b !is IrBlockBody || !b.statements.all { st -> st is IrInstanceInitializerCall || (st is IrDelegatingConstructorCall && isStdlibDecl(st.callee)) })) r = 0
        }
    }
    for ((i, d) in decls.withIndex()) {
        if (i >= r) break
        val ok = when (d) {
            is IrProperty -> { val f = d.backingField; f == null || isSimpleInit(f.initializer?.expression) }
            is IrField -> isSimpleInit(d.initializer?.expression)
            is IrAnonymousInitializer -> d.body.statements.isEmpty()
            else -> true
        }
        if (!ok) r = i
    }
    simpleInitCache[parent] = r
    return r
}

/** [f] of [parent] (a file or an object) is assigned before the initialization runs any user code. */
private fun Ir2Mir.assignedBeforeUserCode(parent: IrDeclarationParent, f: IrField): Boolean {
    val limit = firstNonSimpleInit(parent)
    if (limit == Int.MAX_VALUE) return true
    val decls: List<IrDeclaration> = if (parent is IrFile) parent.declarations else if (parent is IrClass) parent.declarations else return false
    for ((i, d) in decls.withIndex()) {
        val b = if (d is IrProperty) d.backingField else if (d is IrField) d else null
        if (b === f) return i < limit
    }
    return false
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
        return if (init is IrConst || assignedBeforeUserCode(parent, f)) v else MirExpr.NotNull(v)
    }
    val owner = parent as? IrClass ?: return v
    if (owner !in classInfos) return v
    if (owner.kind == IrClassKind.OBJECT) {
        val init = f.initializer?.expression
        if (init != null && init !is IrConst && !assignedBeforeUserCode(owner, f)) return MirExpr.NotNull(v)
    }
    if (!initLeaksThis(owner)) return v
    val need = unsetGuardCache[f] ?: fieldMayBeUnset(owner, f).also { unsetGuardCache[f] = it }
    return if (need) MirExpr.NotNull(v) else v
}

/**
 * A field that an initializer assigns before the first use of `this` by the class's own initialization, or that every constructor
 * assigns before its first use of `this`, is set whenever other code can see the object - unless a superclass lets `this` out (its
 * initialization runs before every initializer and constructor body of this class).
 */
private fun Ir2Mir.fieldMayBeUnset(owner: IrClass, f: IrField): Boolean {
    for (t in owner.superTypes) {
        val sc = t.classOrNull ?: continue
        if (sc !== owner && sc.kind != IrClassKind.INTERFACE && initLeaksThis(sc)) return true
    }
    val leakAt = initLeakIndex(owner)
    val at = initializedAt(owner, f)
    if (at >= 0) return at >= leakAt
    return !(leakAt == owner.declarations.size && everyConstructorAssigns(owner, f, 0))
}

private fun everyConstructorAssigns(owner: IrClass, f: IrField, depth: Int): Boolean {
    var any = false
    for (d in owner.declarations) {
        if (d !is IrConstructor) continue
        any = true
        if (!constructorAssigns(owner, d, f, depth)) return false
    }
    return any
}

/** [ctor] assigns [f] before any statement that uses `this` for more than reading or writing its own fields (or delegates to a constructor of the class that does). */
private fun constructorAssigns(owner: IrClass, ctor: IrConstructor, f: IrField, depth: Int): Boolean {
    if (depth > 8) return false
    val body = ctor.body as? IrBlockBody ?: return false
    val owners = HashSet<IrValueDeclaration>()
    val tr = owner.thisReceiver
    if (tr != null) owners.add(tr)
    for (p in ctor.parameters) if (p.kind == IrParameterKind.DispatchReceiver) owners.add(p)
    for (st in body.statements) {
        if (st is IrDelegatingConstructorCall) {
            if (st.callee.parent === owner) return constructorAssigns(owner, st.callee, f, depth + 1)
            continue
        }
        if (st is IrInstanceInitializerCall) continue // (no initializer of the class leaks: the caller checked)
        if (st is IrCall && st.callee.correspondingProperty?.backingField === f && st.arguments.size == 2 && isFieldAccessor(st.callee)) {
            val r = st.arguments[0]
            val v = st.arguments[1]
            if (r is IrGetValue && r.owner in owners && v != null) {
                val w = ThisUse(owners)
                w.visit(v)
                return !w.found
            }
        }
        if (st is IrSetField && st.field === f) {
            val r = st.receiver
            if (r is IrGetValue && r.owner in owners) {
                val w = ThisUse(owners)
                w.visit(st.value)
                return !w.found
            }
        }
        val w = ThisUse(owners)
        w.visit(st)
        if (w.found) return false
    }
    return false
}

private fun Ir2Mir.inheritsFrom(c: IrClass, p: IrClass, depth: Int = 0): Boolean {
    if (c === p) return true
    if (depth > 32) return false
    for (t in c.superTypes) {
        val s = t.classOrNull ?: continue
        if (s !== c && inheritsFrom(s, p, depth + 1)) return true
    }
    return false
}

/**
 * The call [c] of a getter that can be overridden (so it is not inlined as a field read) of a non-null property whose class, or a subclass, lets `this` out
 * of its initialization: its result is checked against null here, because the default getters of such classes do not check (a read of the property as
 * the receiver of a safe call, `z?.b?.c`, must see the null like the JVM does; a read that uses the value is a `NullPointerException`).
 */
internal fun Ir2Mir.guardUnsetGetterResult(c: IrCall, v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    val callee = c.callee
    if (callee.correspondingProperty == null || !callee.name.startsWith("<get-") || callee.modality == IrModality.FINAL) return v
    if (c in unguardedReads) return v
    val t = v.type
    if (!(t === TString || t is TObject && !t.nullable)) return v
    val kt = c.type
    if (kt.isMarkedNullable() || !(kt is ClassType || kt is FunType)) return v
    val p = callee.parent as? IrClass ?: return v
    if (p !in classInfos) return v
    val need = getterGuardCache.getOrPut(p) { classInfos.keys.any { initLeaksThis(it) && inheritsFrom(it, p) } }
    return if (need) MirExpr.NotNull(if (v.isOwned()) tmpOf(v, out) else v) else v
}

/** The read of [f] in its own default getter (a getter that can be overridden): the callers check ([guardUnsetGetterResult]). */
internal fun Ir2Mir.inOwnOverridableGetter(f: IrField): Boolean {
    val fn = currentFunction as? IrSimpleFunction ?: return false
    return fn.origin == "DEFAULT_PROPERTY_ACCESSOR" && fn.modality != IrModality.FINAL && fn.correspondingProperty?.backingField === f
}
