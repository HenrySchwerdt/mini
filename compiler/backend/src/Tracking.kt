package mini.backend

/*
 * Strict mode in the code generation (docs/memory-model.md, compiler/README.md "Strict code generation").
 *
 * A *tracked* value is an instance of a class declared in Strict code ([MirClass.strict]). In a Strict function
 * ([MirFunction.strict]) tracked values carry no reference counts at all: the ownership checker (memory/) has
 * proved who owns the object, who may read it and who frees it, so the generated C neither retains nor releases
 * locals, parameters, fields or results of tracked type; `x.free()` is `mk_free`, and objects of Scoped allocators
 * (arenas) are never touched individually. Everything that is not tracked (strings, boxes, instances of Managed
 * classes) and everything in Managed functions keeps reference counting (including a Managed function that holds
 * a Strict object it got from an Rc allocation or as a borrowed result: the object has a reference count of its own).
 *
 * The decisions live here so that [CEmitter] and [RcOpt] agree on them.
 */
class Tracking(module: MirModule, val enabled: Boolean = true) {
    private val strictClasses = HashSet<String>()
    private val functions = HashMap<String, MirFunction>()
    private val classes = HashMap<String, MirClass>()

    init {
        for (c in module.classes) {
            classes[c.cName] = c
            if (enabled && c.strict) strictClasses += c.cName
        }
        for (f in module.functions) functions[f.cName] = f
    }

    val any: Boolean get() = strictClasses.isNotEmpty()

    /** Calling conventions of the stdlib hot paths (borrowed results, owned parameters); set by [RcOpt.analyze]. */
    var conv: Conventions? = null

    fun classOf(cName: String): MirClass? = classes[cName]

    fun functionOf(cName: String): MirFunction? = functions[cName]

    /** An instance of a Strict class (or of a Strict interface). */
    fun isTrackedType(t: MirType): Boolean = t is TObject && t.cName in strictClasses

    /** True when values of type [t] are reference counted inside function [f] (null = outside any function, class glue). */
    fun rc(f: MirFunction?, t: MirType): Boolean = t.isRef && !(f != null && f.strict && isTrackedType(t))

    /**
     * True when [e] is an owned (+1) reference in function [f]: the producer's [isOwned] minus the results that are
     * tracked in [f] (no counts there) and minus the unowned results of Strict callees (a Strict function returns
     * its tracked result without a count: either a lent reference or, for owned results, an object whose single
     * owner is the caller).
     */
    fun owned(f: MirFunction?, e: MirExpr): Boolean {
        if (!e.isOwned()) return false
        if (e is MirExpr.CaughtException) return true
        if (conv?.borrowedResult(e) == true) return false
        if (e is MirExpr.New && e.auto && f != null && f.strict && enabled) return true
        if (!rc(f, e.type)) return false
        if (!isTrackedType(e.type)) return true
        return when (e) {
            is MirExpr.Call -> functions[e.cName]?.let { !it.strict } ?: true
            is MirExpr.VCall -> !isTrackedType(e.receiver.type)
            is MirExpr.ICall -> !isTrackedType(e.receiver.type)
            else -> true
        }
    }

    private val autoCache = HashMap<String, Set<Int>>()
    private val tempCache = HashMap<String, Set<Int>>()

    /**
     * The compiler temporaries of [f] (by local id) that hold a fresh object of tracked class (`Item(5)` as an argument: `tmp = Item(5); Cell(tmp)`).
     * The object has its count of one. When the temporary is handed to a parameter that takes a reference (a generic `T` field of a container,
     * which counts) that count is the one handed over, no retain: otherwise the owner's count would be left behind forever.
     */
    private fun moveTemps(f: MirFunction): Set<Int> {
        autoLocals(f)
        return tempCache[f.cName] ?: emptySet()
    }

    /** [e] is such a temporary of [f]: handing it to an owned reference parameter moves its count instead of retaining. */
    fun movedTemp(f: MirFunction?, e: MirExpr): Boolean = f != null && f.strict && enabled && e is MirExpr.Local && e.local.id in moveTemps(f)

    /**
     * Locals of [f] (by id) that receive a `New` with [MirExpr.New.auto]: the stack-like objects of Strict code (no
     * allocator visible, no escape). They keep reference counting (a count of one, released at the end of the scope):
     * the simplest correct lifetime for a value nobody frees explicitly.
     */
    fun autoLocals(f: MirFunction?): Set<Int> {
        if (f == null || !enabled || !f.strict) return emptySet()
        return autoCache.getOrPut(f.cName) {
            val ids = HashSet<Int>()
            val temps = HashSet<Int>()
            collectAuto(f.body, ids, temps)
            tempCache[f.cName] = temps
            ids
        }
    }

    private fun collectAuto(list: List<MirStmt>, ids: HashSet<Int>, temps: HashSet<Int>) {
        for (s in list) {
            when (s) {
                // a caught exception is owned (+1) by its local; of a tracked class it still gets a count that the scope releases
                is MirStmt.Declare -> {
                    val init = s.init
                    if (isAuto(init) || init is MirExpr.CaughtException) ids += s.local.id
                    else if (init is MirExpr.New && s.local.name.startsWith("tmp") && isTrackedType(init.type)) temps += s.local.id
                }
                is MirStmt.Assign -> if (isAuto(s.value)) ids += s.local.id
                is MirStmt.If -> { collectAuto(s.thenBody, ids, temps); collectAuto(s.elseBody, ids, temps) }
                is MirStmt.Block -> collectAuto(s.body, ids, temps)
                is MirStmt.While -> collectAuto(s.body, ids, temps)
                is MirStmt.Loop -> collectAuto(s.body, ids, temps)
                is MirStmt.DoWhile -> { collectAuto(s.body, ids, temps); collectAuto(s.condStmts, ids, temps) }
                is MirStmt.ForRange -> collectAuto(s.body, ids, temps)
                is MirStmt.Try -> { collectAuto(s.body, ids, temps); for (c in s.catches) collectAuto(c.body, ids, temps) }
                else -> {}
            }
        }
    }

    private fun isAuto(e: MirExpr?): Boolean = e is MirExpr.New && e.auto

    /** True when the local [l] of [f] holds a reference count (types that are reference counted there, and auto locals). */
    fun rcLocal(f: MirFunction?, l: MirLocal): Boolean = rc(f, l.type) || l.id in autoLocals(f)

    /** `@Borrowed` field of a Strict class: a non-owning reference, never counted (not by stores, not by dispose). */
    fun borrowedField(owner: String, field: String): Boolean {
        if (!enabled) return false
        val c = classes[owner] ?: return false
        // closures of Strict code (not Strict classes themselves) borrow their captured Strict objects, too
        for (f in c.fields) if (f.name == field) return f.borrowed && f.type.isRef && (c.strict || isTrackedType(f.type))
        return false
    }

    /** The store [s] into a reference field counts references (retains the new value, releases the old one) in [f]. */
    fun rcStore(f: MirFunction?, s: MirStmt.SetField): Boolean = rc(f, s.fieldType) && !borrowedField(s.owner, s.field)

    /** The field is a tracked, owned field of a Strict class: `free()` of the object frees it, too. */
    fun ownedTrackedField(owner: MirClass, f: MirField): Boolean = enabled && owner.strict && !f.borrowed && isTrackedType(f.type)

    /** The field holds a reference count in the object of class [owner] (released by its dispose). */
    fun rcField(owner: MirClass, f: MirField): Boolean =
        f.type.isRef && !(enabled && ((owner.strict && (isTrackedType(f.type) || f.borrowed)) || (f.borrowed && isTrackedType(f.type))))
}

/** A reference of this type can be part of a reference cycle (cycle collection traces it): strings and boxed primitives hold no references. */
fun cycleCapableType(t: MirType): Boolean = t.isRef && t !== TString && t !== TNString && t !== TNull && t !is TBoxed

/**
 * Which classes of a program can never be part of a reference cycle (docs/memory-model.md, "Cycles"): their instances are not
 * candidates for the cycle collector (no trace function, never armed), so releasing them costs nothing extra. A class is acyclic
 * when none of the fields it declares or inherits can lead back to an object that holds references: every field is a string, a
 * boxed primitive, a primitive array, or a reference to a *final* acyclic class of the program (a class without subclasses: a
 * subclass could add a field that closes a cycle). `Any`, interfaces, object arrays, classes defined by the runtime and classes
 * on a cycle of field types are cycle capable. Decided once per class at compile time, the same way by both backends.
 */
class CycleAnalysis(private val classes: Map<String, MirClass>) {
    private val memo = HashMap<String, Boolean>() // class C name -> acyclic
    private val visiting = HashSet<String>()
    private var subclassedSet: Set<String>? = null
    private fun subclassed(): Set<String> {
        var r = subclassedSet
        if (r == null) { r = classes.values.mapNotNull { it.superCName }.toSet(); subclassedSet = r }
        return r
    }

    /** The field [f] of [owner] can lead to a cycle (a reference field that cannot is not traced). */
    fun fieldCapable(owner: MirClass, f: MirField): Boolean {
        // the native buffer of a StringBuilder is typed Any in the library, but it only ever holds characters
        if (owner.qualifiedName == "kotlin.text.StringBuilder" && f.name == "buf") return false
        return typeCapable(f.type)
    }

    private fun typeCapable(t: MirType): Boolean {
        if (!cycleCapableType(t)) return false
        if (t !is TObject) return true
        if (t.cName in PRIMITIVE_ARRAYS) return false
        if (t.isInterface || t.cName == ANY_CNAME) return true
        return !classAcyclic(t.cName)
    }

    private fun classAcyclic(cName: String): Boolean {
        memo[cName]?.let { return it }
        val c = classes[cName] ?: return false
        if (c.external || c.isInterface || cName in subclassed()) return false
        if (!visiting.add(cName)) return false // on a cycle of field types
        var ok = true
        var k: MirClass? = c
        while (ok && k != null) {
            if (k.external || k.isInterface) { ok = false; break }
            for (f in k.fields) if (fieldCapable(k, f)) { ok = false; break }
            k = k.superCName?.let { classes[it] }
        }
        visiting.remove(cName)
        memo[cName] = ok
        return ok
    }

    private companion object {
        val PRIMITIVE_ARRAYS = setOf("MkIntArray", "MkLongArray", "MkDoubleArray", "MkBooleanArray", "MkCharArray", "MkFloatArray", "MkByteArray", "MkShortArray")
    }
}
