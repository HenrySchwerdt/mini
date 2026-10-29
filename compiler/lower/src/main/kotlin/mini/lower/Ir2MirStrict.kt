package mini.lower

import mini.backend.*
import mini.frontend.sema.*

/*
 * Ir2Mir: Strict mode (docs/memory-model.md, compiler/README.md "Strict code generation"). The port of
 * jvm-bridge/Ir2MirStrict.kt: the same decisions on the own IR, so both frontends give the same MIR.
 *
 * What the producer decides: which classes / functions are Strict, which allocator each allocation site of a Strict
 * class in Strict code uses (the rules of section 2.2: innermost context level, `@AllocatedBy` as a restriction), and
 * the lowering of the memory API calls (`free`, `move`). What the emitter does with it (no reference counting for
 * tracked values, `mk_alloc_with`, `mk_free`) is in backend/Tracking.kt and CEmitter.
 *
 * Annotations are matched by their simple name (the own frontend does not resolve annotation classes).
 */

private const val MEMORY_PKG = "mini.memory"

/** An allocator object visible as a context argument: its class and the local that holds it. */
internal class AllocRef(val classFq: String, val local: MirLocal, val ownerCName: String)

private fun modeStrict(a: IrAnnotation): Boolean {
    val v = a.arg ?: return false
    return v != "Managed"
}

internal fun Ir2Mir.fileStrict(f: IrFile?): Boolean {
    if (f == null) return false
    val a = findAnnotation(f.annotations, "MemoryMode") ?: return false
    return modeStrict(a)
}

internal fun Ir2Mir.isStrictClass(c: IrClass): Boolean {
    val cached = strictClassCache[c]
    if (cached != null) return cached
    var strict = false
    val file = Reachability.fileOf(c)
    if (file != null) {
        strict = fileStrict(file)
        val a = findAnnotation(c.annotations, "MemoryMode")
        if (a != null) strict = modeStrict(a)
    }
    strictClassCache[c] = strict
    return strict
}

internal fun Ir2Mir.isStrictFunction(f: IrFunction): Boolean {
    var p: IrDeclarationParent? = f.parent
    while (p != null) {
        if (p is IrClass) return isStrictClass(p)
        if (p is IrFile) return fileStrict(p)
        if (p is IrFunction) p = p.parent else return false
    }
    return false
}

/** `@Borrowed` field of a Strict class (a non-owning back reference); the annotations of the property, the field, the constructor parameter and the type were collected on the field. */
internal fun Ir2Mir.isBorrowedField(f: IrField): Boolean = hasAnnotation(f.annotations, "Borrowed")

// ---- allocator contexts ---------------------------------------------------------------------------------------------

private val ALLOCATOR_CLASSES = setOf("Heap", "Arena", "Pool", "Rc", "DebugAllocator")

/** The `mini.memory` allocator class with a native handle that [t] is, or null (interfaces, user allocators, other types). */
internal fun Ir2Mir.allocatorClassOf(t: Type): IrClass? {
    val c = t.classOrNull ?: return null
    val fq = c.kotlinFqName
    if (!fq.startsWith(MEMORY_PKG + ".") || fq.removePrefix(MEMORY_PKG + ".") !in ALLOCATOR_CLASSES) return null
    return if (c in classInfos) c else null
}

/** Declares the context parameters of the function being converted as the first allocator level. */
internal fun Ir2Mir.enterFunctionContext(f: IrFunction, params: List<MirLocal>) {
    allocLevels.clear()
    currentStrict = isStrictFunction(f)
    if (!currentStrict) return
    val level = ArrayList<AllocRef>()
    val ps = params.takeLast(f.parameters.size)
    for ((i, p) in f.parameters.withIndex()) {
        if (p.kind != IrParameterKind.Context) continue
        val r = allocRefOf(p.type, ps[i])
        if (r != null) level += r
    }
    if (level.isNotEmpty()) allocLevels += level
}

internal fun Ir2Mir.allocRefOf(t: Type, local: MirLocal): AllocRef? {
    val c = allocatorClassOf(t) ?: return null
    return AllocRef(c.kotlinFqName, local, info(c).cName)
}

private fun Ir2Mir.handleOf(a: AllocRef): MirExpr = MirExpr.FieldGet(MirExpr.Local(a.local), a.ownerCName, "handle", TLong)

/**
 * The allocator a new Strict-class object is allocated from (docs/memory-model.md 2.2): the innermost context level
 * that has a candidate wins (`@AllocatedBy` restricts the candidates), else the default instance of `Heap` for
 * `@AllocatedBy(Heap::class)`, else none. Returns the handle expression (Long) and whether the object is stack-like
 * ("auto": no allocator visible, does not escape: it dies with the local that holds it).
 */
internal fun Ir2Mir.resolveAllocation(cls: IrClass): Pair<MirExpr?, Boolean> {
    if (!currentStrict || !isStrictClass(cls)) return Pair(null, false)
    val ab = findAnnotation(cls.annotations, "AllocatedBy")?.arg
    for (level in allocLevels.asReversed()) {
        val cands = level.filter { ab == null || it.classFq == MEMORY_PKG + "." + ab }
        if (cands.isEmpty()) continue
        val a = cands[0]
        return when (a.classFq.removePrefix(MEMORY_PKG + ".")) {
            "Rc" -> Pair(null, false) // reference counted: the default allocation
            else -> Pair(handleOf(a), false)
        }
    }
    if (ab == "Heap") return Pair(MirExpr.RuntimeCall("mk_heap_allocator_h", emptyList(), TLong), false)
    if (ab == "Rc") return Pair(null, false)
    return Pair(null, true)
}

/** Runs [body] with [refs] (the allocator arguments of a `context(a, b) { }` block) as the innermost allocator level. */
internal inline fun <T> Ir2Mir.withAllocatorLevel(refs: List<AllocRef>, body: () -> T): T {
    if (refs.isEmpty()) return body()
    allocLevels += refs
    try {
        return body()
    } finally {
        allocLevels.removeAt(allocLevels.size - 1)
    }
}

// ---- the memory API ---------------------------------------------------------------------------------------------

/**
 * Calls of `mini.memory.free` / `move`. Returns null when [c] is something else.
 */
internal fun Ir2Mir.memoryApiCall(c: IrCall, owner: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr? {
    val parent = owner.parent
    if (parent !is IrFile || parent.packageFqName != MEMORY_PKG) return null
    val n = owner.name
    if (n == "free") {
        if (c.arguments.size != 1) return null
        val target = c.arguments[0] ?: return null
        val v = operands(listOf(target), out)[0]
        if (v.type == TUnit) return MirExpr.UnitValue
        // `x.free()` runs `close()` first when x is AutoCloseable, then the runtime releases the owned fields and
        // returns the memory (a no-op for reference counted objects: they free themselves)
        val cs = closeStmtFor(target.type, v)
        if (cs != null) out += cs
        out += MirStmt.Eval(MirExpr.RuntimeCall("mk_free_dealloc", listOf(v), TUnit))
        return MirExpr.UnitValue
    }
    if (n == "move") {
        if (c.arguments.size != 1) return null
        val v = value(c.arguments[0]!!, out)
        val expected = mirType(c.type, c)
        return if (v.type == expected) v else coerceFlexible(v, expected, c, out)
    }
    return null
}

/** `if (v is AutoCloseable) v.close()`, or null when [t] cannot be closeable (a final class without the interface). */
private fun Ir2Mir.closeStmtFor(t: Type, v: MirExpr): MirStmt? {
    var ac: IrClass? = null
    for (k in classInfos.keys) if (k.kotlinFqName == "kotlin.AutoCloseable") { ac = k; break }
    if (ac == null) return null
    val cls = t.classOrNull
    if (cls != null && cls.modality == IrModality.FINAL && cls.kind == IrClassKind.CLASS &&
        allInterfaces(cls).none { it === ac }
    ) return null
    val idx = interfaceMethods(ac).indexOfFirst { it.name == "close" }
    if (idx < 0) return null
    val acType = typeOf(ac)
    val recv = MirExpr.Cast(v, acType, MirExpr.CastMode.Unchecked)
    val call = MirExpr.ICall(info(ac).cName, idx, recv, emptyList(), emptyList(), TUnit)
    return MirStmt.If(MirExpr.IsInstance(v, acType), listOf(MirStmt.Eval(call)), emptyList())
}

// ---- calling conventions of the stdlib hot paths (backend/Conventions.kt) ---------------------------------------------

/** `@kotlin.internal.ReturnsBorrowed` on the function (or the property of a getter): the result is a borrowed reference. */
internal fun Ir2Mir.returnsBorrowedAnnotation(f: IrFunction): Boolean {
    if (hasAnnotation(f.annotations, "ReturnsBorrowed")) return true
    val prop = (f as? IrSimpleFunction)?.correspondingProperty
    return prop != null && hasAnnotation(prop.annotations, "ReturnsBorrowed")
}

/** Indices (into the MIR parameter list [params], which may have a constructor's `this` in front) of `@TakesOwnership` parameters. */
internal fun Ir2Mir.ownedParamIndexes(f: IrFunction, params: List<MirLocal>): List<Int> {
    val offset = params.size - f.parameters.size
    val out = ArrayList<Int>()
    for ((i, p) in f.parameters.withIndex()) if (hasAnnotation(p.annotations, "TakesOwnership")) out += offset + i
    return out
}

/** `@kotlin.internal.NoHeapEffects` (stdlib functions that only change objects they create): calls do not end the lifetime of borrowed aliases. */
internal fun Ir2Mir.noHeapEffectsAnnotation(f: IrFunction): Boolean {
    if (hasAnnotation(f.annotations, "NoHeapEffects")) return true
    val prop = (f as? IrSimpleFunction)?.correspondingProperty
    return prop != null && hasAnnotation(prop.annotations, "NoHeapEffects")
}
