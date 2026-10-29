package mini.backend

/*
 * Heap effects for RcOpt: which references stored in the heap can an operation overwrite?
 *
 * A borrowed alias (RcOpt rule 1) `x = a.b.c` stays valid while nothing removes the last reference to the object it
 * points to. Only an overwritten heap reference can do that (a reference field store, a reference array slot store);
 * releasing a local's reference never frees an object another owner still holds. An alias read through the fields
 * `b` and `c` can therefore only be invalidated by a store into a field `b` or `c` (of any object of that class), by an
 * array slot store when the chain reads a slot, or by something unknown (a runtime function that is not known to be
 * pure). [Eff] is such a set: the ids of the reference fields that may be stored, whether array slots may be, whether
 * anything else may happen. [RcEffects] computes it per function as a fixpoint over the call graph (virtual and
 * interface calls through the union over the implementations of their dispatch group).
 */
internal class Eff {
    var bits: LongArray = EMPTY_BITS
    var slots = false
    var unknown = false

    fun isEmpty(): Boolean {
        if (slots || unknown) return false
        for (w in bits) if (w != 0L) return false
        return true
    }

    fun hasField(): Boolean {
        for (w in bits) if (w != 0L) return true
        return false
    }

    fun addField(id: Int): Boolean {
        val w = id ushr 6
        if (w >= bits.size) {
            val n = LongArray(w + 1)
            for (i in bits.indices) n[i] = bits[i]
            bits = n
        }
        val m = 1L shl (id and 63)
        if (bits[w] and m != 0L) return false
        bits[w] = bits[w] or m
        return true
    }

    /** Adds everything of [o]; true when something changed. */
    fun orWith(o: Eff): Boolean {
        var ch = false
        if (o.slots && !slots) { slots = true; ch = true }
        if (o.unknown && !unknown) { unknown = true; ch = true }
        val ob = o.bits
        if (ob.size > bits.size) {
            val n = LongArray(ob.size)
            for (i in bits.indices) n[i] = bits[i]
            bits = n
        }
        for (i in ob.indices) {
            val nw = bits[i] or ob[i]
            if (nw != bits[i]) { bits[i] = nw; ch = true }
        }
        return ch
    }

    /** True when an operation with effect [this] can invalidate an alias that depends on [sens]. */
    fun hits(sens: Eff): Boolean {
        if (unknown) return true
        if (slots && (sens.slots || sens.unknown)) return true
        if (sens.unknown) return hasField()
        val n = if (bits.size < sens.bits.size) bits.size else sens.bits.size
        for (i in 0 until n) if (bits[i] and sens.bits[i] != 0L) return true
        return false
    }

    companion object {
        val EMPTY_BITS = LongArray(0)
    }
}

/**
 * Per-function [Eff] of a module. [mutating] is the compatibility view: the functions with a non-empty effect.
 */
internal class RcEffects(private val module: MirModule, private val tr: Tracking) {
    val mutating = HashSet<String>()
    private val groups = DispatchGroups(module)
    private val objectCtors = HashMap<String, String>()
    private val fieldIds = HashMap<String, Int>()
    private val fieldEffs = ArrayList<Eff>()
    private val eff = HashMap<String, Eff>()
    private val groupEff = HashMap<String, Eff>()
    private val anySlotEff = arrayOfNulls<Eff>(3)
    private var allDynEff: Eff? = null
    private val byName = HashMap<String, MirFunction>()
    private val edgeMap = HashMap<String, ArrayList<Any>>()

    fun fieldId(owner: String, field: String): Int {
        val key = "$owner#$field"
        return fieldIds.getOrPut(key) { fieldIds.size }
    }

    /** The effect "stores into field [id]" (a shared object: do not modify). */
    fun fieldEff(id: Int): Eff {
        while (fieldEffs.size <= id) fieldEffs.add(Eff())
        val e = fieldEffs[id]
        if (e.bits.isEmpty()) e.addField(id)
        return e
    }

    private val slotEff = Eff().also { it.slots = true }
    private val unknownEff = Eff().also { it.unknown = true }

    fun run(): RcEffects {
        for (f in module.functions) byName[f.cName] = f
        for (c in module.classes) {
            val oc = c.objectCtor
            if (oc != null) objectCtors[c.cName] = oc
        }
        // local effects and call edges of every function
        val local = HashMap<String, Eff>()
        val edges = HashMap<String, ArrayList<Any>>()
        for (f in module.functions) {
            val l = Eff()
            val es = ArrayList<Any>()
            if (!f.noHeapEffects) scan(f, l, es)
            local[f.cName] = l
            edges[f.cName] = es
            edgeMap[f.cName] = es
            eff[f.cName] = Eff().also { it.orWith(l) }
        }
        // fixpoint: eff(f) = local(f) + eff(callees); dispatch groups through the union of their members
        var changed = true
        while (changed) {
            changed = false
            groupEff.clear()
            anySlotEff.fill(null)
            allDynEff = null
            for (f in module.functions) {
                val mine = eff[f.cName]!!
                for (edge in edges[f.cName]!!) {
                    val callee = when (edge) {
                        is String -> eff[edge]
                        is Edge -> edge.eff(this)
                        else -> null
                    } ?: continue
                    if (mine.orWith(callee)) changed = true
                }
            }
        }
        for (f in module.functions) if (!eff[f.cName]!!.isEmpty()) mutating += f.cName
        return this
    }

    /** An edge to a set of functions (a dispatch group); evaluated lazily against the current effects. */
    internal class Edge(val targets: Set<String>?, val kind: Int, val key: String? = null) {
        fun eff(fx: RcEffects): Eff? = when (kind) {
            0 -> fx.groupUnionByKey(key!!, targets!!)
            1 -> fx.anySlotUnion(0)
            2 -> fx.anySlotUnion(1)
            3 -> fx.anySlotUnion(2)
            else -> fx.allDynamicUnion()
        }
    }

    internal fun groupUnionByKey(key: String, targets: Set<String>): Eff = groupEff.getOrPut(key) { unionOf(targets) }

    internal fun unionOf(targets: Set<String>): Eff {
        val u = Eff()
        for (t in targets) eff[t]?.let { u.orWith(it) }
        return u
    }

    internal fun anySlotUnion(k: Int): Eff = anySlotEff[k] ?: unionOf(groups.anySlot[k]).also { anySlotEff[k] = it }

    internal fun allDynamicUnion(): Eff = allDynEff ?: Eff().also { u ->
        for (s in groups.allTargets()) for (t in s) eff[t]?.let { u.orWith(it) }
        allDynEff = u
    }

    private fun scan(f: MirFunction, local: Eff, edges: ArrayList<Any>) {
        fun expr(e: MirExpr) {
            call(e, local, edges)
            for (c in mirChildren(e)) expr(c)
        }
        fun stmts(list: List<MirStmt>) {
            for (s in list) {
                when (s) {
                    is MirStmt.Declare -> s.init?.let { expr(it) }
                    is MirStmt.Assign -> expr(s.value)
                    is MirStmt.SetField -> {
                        expr(s.obj); expr(s.value)
                        if (tr.rcStore(f, s) && !isFreshThisStore(f, s)) local.addField(fieldId(s.owner, s.field))
                    }
                    is MirStmt.Eval -> expr(s.expr)
                    is MirStmt.If -> { expr(s.cond); stmts(s.thenBody); stmts(s.elseBody) }
                    is MirStmt.Block -> stmts(s.body)
                    is MirStmt.While -> { expr(s.cond); stmts(s.body) }
                    is MirStmt.Loop -> stmts(s.body)
                    is MirStmt.DoWhile -> { stmts(s.body); stmts(s.condStmts); expr(s.cond) }
                    is MirStmt.ForRange -> { expr(s.start); expr(s.end); s.step?.let { expr(it) }; stmts(s.body) }
                    is MirStmt.Return -> s.value?.let { expr(it) }
                    is MirStmt.Throw -> expr(s.value)
                    is MirStmt.Try -> { stmts(s.body); for (c in s.catches) stmts(c.body) }
                    is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> {}
                }
            }
        }
        stmts(f.body)
    }

    /** Adds the effect of the call-like expression [e] itself (not its operands) to [local] / [edges]. */
    private fun call(e: MirExpr, local: Eff, edges: ArrayList<Any>) {
        when (e) {
            is MirExpr.Call -> edges += e.cName
            is MirExpr.New -> edges += e.ctorCName
            is MirExpr.VCall -> dyn(e, local, edges)
            is MirExpr.ICall -> dyn(e, local, edges)
            is MirExpr.RuntimeCall -> runtime(e.cName, local, edges)
            // the first access of an object runs its constructor
            is MirExpr.ObjectGet -> objectCtors[e.classCName]?.let { edges += it }
            else -> {}
        }
    }

    private fun dyn(e: MirExpr, local: Eff, edges: ArrayList<Any>) {
        if (e is MirExpr.VCall && e.slot < 3) {
            edges += Edge(null, 1 + e.slot)
            return
        }
        val k = groups.key(e)
        if (k == null) edges += Edge(null, 4) else edges += Edge(groups.targets(e), 0, k)
    }

    private fun runtime(name: String, local: Eff, edges: ArrayList<Any>) {
        when {
            name == "mk_to_string" -> edges += Edge(null, 1)
            name == "mk_equals" -> edges += Edge(null, 2)
            name == "mk_hash_code" -> edges += Edge(null, 3)
            name == "mk_compare_to" || name == "mk_array_content_to_string" -> edges += Edge(null, 4)
            name == "mk_ref_array_set" || name == "mk_ref_array_set_o" -> local.slots = true
            isPureRuntime(name) -> {}
            else -> local.unknown = true
        }
    }

    private fun isFreshThisStore(f: MirFunction, s: MirStmt.SetField): Boolean {
        if (f.constructorOf == null || f.params.isEmpty()) return false
        val o = s.obj
        return o is MirExpr.Local && o.local.id == f.params[0].id
    }

    // ---- queries for the analyzers --------------------------------------------------------------------------

    /** The effect of evaluating the call-like expression [e] itself, or null when it cannot overwrite a heap reference. */
    fun callEff(e: MirExpr): Eff? {
        val r: Eff? = when (e) {
            is MirExpr.Call -> eff[e.cName]
            is MirExpr.New -> eff[e.ctorCName]
            is MirExpr.VCall -> if (e.slot < 3) anySlotUnion(e.slot) else if (groups.key(e) == null) allDynamicUnion() else groupUnion(e)
            is MirExpr.ICall -> groupUnion(e)
            is MirExpr.RuntimeCall -> runtimeEff(e.cName)
            is MirExpr.ObjectGet -> objectCtors[e.classCName]?.let { eff[it] }
            else -> null
        }
        return if (r == null || r.isEmpty()) null else r
    }

    private fun groupUnion(e: MirExpr): Eff {
        val k = groups.key(e) ?: return allDynamicUnion()
        return groupUnionByKey(k, groups.targets(e))
    }

    private fun runtimeEff(name: String): Eff? = when {
        name == "mk_to_string" -> anySlotUnion(0)
        name == "mk_equals" -> anySlotUnion(1)
        name == "mk_hash_code" -> anySlotUnion(2)
        name == "mk_compare_to" || name == "mk_array_content_to_string" -> allDynamicUnion()
        name == "mk_ref_array_set" || name == "mk_ref_array_set_o" -> slotEff
        isPureRuntime(name) -> null
        else -> unknownEff
    }

    /** The effect of function [cName] as text (for `mem-report`): the fields it may store into, array slots, unknown. */
    fun describe(cName: String): String {
        val e = eff[cName] ?: return "?"
        if (e.isEmpty()) return "none"
        val names = ArrayList<String>()
        for ((k, id) in fieldIds) if (id ushr 6 < e.bits.size && (e.bits[id ushr 6] ushr (id and 63)) and 1L != 0L) names += k
        names.sort()
        return (names.joinToString(", ") + (if (e.slots) " [array slots]" else "") + (if (e.unknown) " [unknown]" else "")).trim()
    }

    /** Debug: a call path from [cName] to a function that stores into [fieldKey] (`Owner#field`), or null. */
    fun tracePath(cName: String, fieldKey: String): String? {
        val id = fieldIds[fieldKey] ?: return null
        val seen = HashSet<String>()
        fun has(e: Eff?) = e != null && id ushr 6 < e.bits.size && (e.bits[id ushr 6] ushr (id and 63)) and 1L != 0L
        fun go(n: String, depth: Int): String? {
            if (depth > 12 || !seen.add(n)) return null
            val f = byName[n] ?: return null
            val l = Eff(); val es = ArrayList<Any>()
            scan(f, l, es)
            if (has(l)) return f.qualifiedName
            for (edge in es) {
                if (edge is String) {
                    if (has(eff[edge])) go(edge, depth + 1)?.let { return f.qualifiedName + " -> " + it }
                } else if (edge is Edge) {
                    val targets = edge.targets ?: (if (edge.kind in 1..3) groups.anySlot[edge.kind - 1] else groups.allTargets().flatMap { it.asSequence() }.toSet())
                    for (t in targets) if (has(eff[t])) go(t, depth + 1)?.let { return f.qualifiedName + " ~> " + it }
                }
            }
            return null
        }
        return go(cName, 0)
    }

    /** The first reason [f] has a heap effect (for `mem-report`), or null. */
    fun explain(f: MirFunction): String? {
        val l = Eff()
        val es = ArrayList<Any>()
        scan(f, l, es)
        if (l.unknown) return "calls a runtime function that may overwrite references"
        if (l.slots) return "stores into a reference array"
        if (l.hasField()) return "stores a reference into a field"
        for (edge in es) {
            val callee = when (edge) {
                is String -> eff[edge]
                is Edge -> edge.eff(this)
                else -> null
            }
            if (callee != null && !callee.isEmpty()) return if (edge is String) "calls $edge" else "calls through a dispatch group"
        }
        return null
    }

    companion object {
        /** Runtime functions that never overwrite a reference stored in a field or an array slot of an existing object. */
        fun isPureRuntime(name: String): Boolean =
            name.startsWith("mk_string_") || name.startsWith("mk_print") || name.startsWith("mk_box_") ||
                name.startsWith("mk_checked_") || name == "mk_is_instance" || name == "mk_ref_array_get" ||
                name == "mk_ref_array_length" || name == "mk_ref_array_get_b" || name == "mk_ref_array_new" || name.startsWith("mk_array_new") ||
                name == "mk_array_length" || name.startsWith("mk_array_get_") ||
                name == "mk_array_set_int" || name == "mk_array_set_long" || name == "mk_array_set_double" ||
                name == "mk_array_set_boolean" || name == "mk_array_set_char" || name.startsWith("mk_sb_") ||
                name == "mk_type_name" || name == "mk_identity_hash" || name == "mk_any_new" || name.startsWith("mk_any_") ||
                name.startsWith("mk_b1_") || name.startsWith("mk_raise") || name == "mk_throwable_init" ||
                (name.startsWith("Mk") && name.endsWith("_new"))
    }
}
