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

    /**
     * Locals of [f] (by id) that receive a `New` with [MirExpr.New.auto]: the stack-like objects of Strict code (no
     * allocator visible, no escape). They keep reference counting (a count of one, released at the end of the scope):
     * the simplest correct lifetime for a value nobody frees explicitly.
     */
    fun autoLocals(f: MirFunction?): Set<Int> {
        if (f == null || !enabled || !f.strict) return emptySet()
        return autoCache.getOrPut(f.cName) {
            val ids = HashSet<Int>()
            collectAuto(f.body, ids)
            ids
        }
    }

    private fun collectAuto(list: List<MirStmt>, ids: HashSet<Int>) {
        for (s in list) {
            when (s) {
                is MirStmt.Declare -> if (isAuto(s.init)) ids += s.local.id
                is MirStmt.Assign -> if (isAuto(s.value)) ids += s.local.id
                is MirStmt.If -> { collectAuto(s.thenBody, ids); collectAuto(s.elseBody, ids) }
                is MirStmt.Block -> collectAuto(s.body, ids)
                is MirStmt.While -> collectAuto(s.body, ids)
                is MirStmt.Loop -> collectAuto(s.body, ids)
                is MirStmt.DoWhile -> { collectAuto(s.body, ids); collectAuto(s.condStmts, ids) }
                is MirStmt.ForRange -> collectAuto(s.body, ids)
                is MirStmt.Try -> { collectAuto(s.body, ids); for (c in s.catches) collectAuto(c.body, ids) }
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
        if (!c.strict) return false
        for (f in c.fields) if (f.name == field) return f.borrowed && f.type.isRef
        return false
    }

    /** The store [s] into a reference field counts references (retains the new value, releases the old one) in [f]. */
    fun rcStore(f: MirFunction?, s: MirStmt.SetField): Boolean = rc(f, s.fieldType) && !borrowedField(s.owner, s.field)

    /** The field is a tracked, owned field of a Strict class: `free()` of the object frees it, too. */
    fun ownedTrackedField(owner: MirClass, f: MirField): Boolean = enabled && owner.strict && !f.borrowed && isTrackedType(f.type)

    /** The field holds a reference count in the object of class [owner] (released by its dispose). */
    fun rcField(owner: MirClass, f: MirField): Boolean =
        f.type.isRef && !(enabled && owner.strict && (isTrackedType(f.type) || f.borrowed))
}
