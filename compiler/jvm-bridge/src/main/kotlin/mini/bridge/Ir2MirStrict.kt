@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.MirExpr
import mini.backend.MirLocal
import mini.backend.MirStmt
import mini.backend.TLong
import mini.backend.TUnit
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.IrElement

/*
 * Ir2Mir: Strict mode (docs/memory-model.md, compiler/README.md "Strict code generation").
 *
 * What the producer decides: which classes / functions are Strict, which allocator each allocation site of a Strict
 * class in Strict code uses (the rules of section 2.2: innermost context level, `@AllocatedBy` as a restriction), and
 * the lowering of the memory API calls (`free`, `move`, allocator constructors, `close`). What the emitter does with
 * it (no reference counting for tracked values, `mk_alloc_with`, `mk_free`) is in backend/Tracking.kt and CEmitter.
 */

private const val MEMORY_MODE = "mini.memory.MemoryMode"
private const val ALLOCATED_BY = "mini.memory.AllocatedBy"
private const val BORROWED = "mini.memory.Borrowed"
private const val MEMORY_PKG = "mini.memory"

/** An allocator object visible as a context argument: its class and the local that holds it. */
internal class AllocRef(val classFq: String, val local: MirLocal, val ownerCName: String)

private fun List<org.jetbrains.kotlin.ir.expressions.IrConstructorCall>.anno(fq: String): IrConstructorCall? =
    firstOrNull { it.type.classFqName?.asString() == fq }

private fun modeStrict(a: IrConstructorCall): Boolean? {
    val v = (a.arguments.firstOrNull() as? IrGetEnumValue)?.symbol?.owner?.name?.asString() ?: return null
    return v != "Managed"
}

internal fun Ir2Mir.fileStrict(f: IrFile?): Boolean {
    if (f == null) return false
    val a = f.annotations.anno(MEMORY_MODE) ?: return false
    return modeStrict(a) ?: false
}

internal fun Ir2Mir.isStrictClass(c: IrClass): Boolean = strictClassCache.getOrPut(c) {
    val file = c.fileOrNull ?: return@getOrPut false
    var strict = fileStrict(file)
    c.annotations.anno(MEMORY_MODE)?.let { a -> modeStrict(a)?.let { strict = it } }
    strict
}

internal fun Ir2Mir.isStrictFunction(f: IrFunction): Boolean {
    var p: IrDeclarationParent? = f.parent
    while (p != null) {
        when (p) {
            is IrClass -> return isStrictClass(p)
            is IrFile -> return fileStrict(p)
            is IrFunction -> p = p.parent
            else -> return false
        }
    }
    return false
}

/** `@Borrowed` field of a Strict class (a non-owning back reference). */
internal fun Ir2Mir.isBorrowedField(f: IrField): Boolean {
    val prop: IrProperty? = f.correspondingPropertySymbol?.owner
    if (prop != null && prop.annotations.anno(BORROWED) != null) return true
    if (f.annotations.anno(BORROWED) != null || f.type.annotations.anno(BORROWED) != null) return true
    val cls = f.parent as? IrClass ?: return false
    val ctor = cls.declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary } ?: return false
    val p = ctor.parameters.firstOrNull { it.name == f.name } ?: return false
    return p.annotations.anno(BORROWED) != null || p.type.annotations.anno(BORROWED) != null
}

// ---- allocator contexts ---------------------------------------------------------------------------------------------

private val ALLOCATOR_CLASSES = setOf("Heap", "Arena", "Pool", "Rc", "DebugAllocator")

/** The `mini.memory` allocator class with a native handle that [t] is, or null (interfaces, user allocators, other types). */
internal fun Ir2Mir.allocatorClassOf(t: IrType): IrClass? {
    val c = t.classOrNull?.owner ?: return null
    val fq = c.kotlinFqName.asString()
    if (!fq.startsWith("$MEMORY_PKG.") || fq.removePrefix("$MEMORY_PKG.") !in ALLOCATOR_CLASSES) return null
    return if (c in classInfos) c else null
}

/** True when [t] is an allocator type (even one without a native handle): it takes part in the allocator resolution. */
internal fun Ir2Mir.isAllocatorType(t: IrType): Boolean {
    val c = t.classOrNull?.owner ?: return false
    val fq = c.kotlinFqName.asString()
    if (fq.startsWith("$MEMORY_PKG.") && fq.removePrefix("$MEMORY_PKG.") in ALLOCATOR_CLASSES) return true
    return false
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
        level += allocRefOf(p.type, ps[i]) ?: continue
    }
    if (level.isNotEmpty()) allocLevels += level
}

internal fun Ir2Mir.allocRefOf(t: IrType, local: MirLocal): AllocRef? {
    val c = allocatorClassOf(t) ?: return null
    return AllocRef(c.kotlinFqName.asString(), local, info(c).cName)
}

private fun Ir2Mir.handleOf(a: AllocRef): MirExpr = MirExpr.FieldGet(MirExpr.Local(a.local), a.ownerCName, "handle", TLong)

/**
 * The allocator a new Strict-class object is allocated from (docs/memory-model.md 2.2): the innermost context level
 * that has a candidate wins (`@AllocatedBy` restricts the candidates), else the default instance of `Heap` for
 * `@AllocatedBy(Heap::class)`, else none. Returns the handle expression (Long) and whether the object is stack-like
 * ("auto": no allocator visible, does not escape: it dies with the local that holds it).
 */
internal fun Ir2Mir.resolveAllocation(cls: IrClass): Pair<MirExpr?, Boolean> {
    if (!currentStrict || !isStrictClass(cls)) return null to false
    val ab = cls.annotations.anno(ALLOCATED_BY)?.let { a ->
        (a.arguments.firstOrNull() as? IrClassReference)?.classType?.classFqName?.asString()
    }
    for (level in allocLevels.asReversed()) {
        val cands = level.filter { ab == null || it.classFq == ab }
        if (cands.isEmpty()) continue
        val a = cands[0]
        return when (a.classFq.removePrefix("$MEMORY_PKG.")) {
            "Rc" -> null to false // reference counted: the default allocation
            else -> handleOf(a) to false
        }
    }
    if (ab == "$MEMORY_PKG.Heap") return MirExpr.RuntimeCall("mk_heap_allocator_h", emptyList(), TLong) to false
    if (ab == "$MEMORY_PKG.Rc") return null to false
    return null to true
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
 * Calls of `mini.memory.free` / `move` and allocator members the compiler knows. Returns null when [c] is something else.
 */
internal fun Ir2Mir.memoryApiCall(c: IrCall, owner: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr? {
    val parent = owner.parent
    if (parent !is org.jetbrains.kotlin.ir.declarations.IrPackageFragment || parent.packageFqName.asString() != MEMORY_PKG) return null
    return when (owner.name.asString()) {
        "free" -> {
            if (c.arguments.size != 1) return null
            val target = c.arguments[0] ?: return null
            val v = operands(listOf(target), out)[0]
            if (v.type == TUnit) return MirExpr.UnitValue
            // `x.free()` runs `close()` first when x is AutoCloseable, then the runtime releases the owned fields and
            // returns the memory (a no-op for reference counted objects: they free themselves)
            closeStmtFor(target.type, v)?.let { out += it }
            out += MirStmt.Eval(MirExpr.RuntimeCall("mk_free_dealloc", listOf(v), TUnit))
            MirExpr.UnitValue
        }
        "move" -> {
            if (c.arguments.size != 1) return null
            val v = value(c.arguments[0]!!, out)
            val expected = mirType(c.type, c)
            if (v.type == expected) v else coerceFlexible(v, expected, c, out)
        }
        else -> null
    }
}

/** `if (v is AutoCloseable) v.close()`, or null when [t] cannot be closeable (a final class without the interface). */
private fun Ir2Mir.closeStmtFor(t: IrType, v: MirExpr): MirStmt? {
    val ac = classInfos.keys.firstOrNull { it.kotlinFqName.asString() == "kotlin.AutoCloseable" } ?: return null
    val cls = t.classOrNull?.owner
    if (cls != null && cls.modality == org.jetbrains.kotlin.descriptors.Modality.FINAL && cls.kind == ClassKind.CLASS &&
        allInterfaces(cls).none { it === ac }
    ) return null
    val idx = interfaceMethods(ac).indexOfFirst { it.name.asString() == "close" }
    if (idx < 0) return null
    val acType = typeOf(ac)
    val recv = MirExpr.Cast(v, acType, MirExpr.CastMode.Unchecked)
    val call = MirExpr.ICall(info(ac).cName, idx, recv, emptyList(), emptyList(), TUnit)
    return MirStmt.If(MirExpr.IsInstance(v, acType), listOf(MirStmt.Eval(call)), emptyList())
}
