package mini.backend.memory

/*
 * Abstract domain of the ownership analysis (docs/memory-model.md 5.2).
 *
 * A tracked slot (local variable / parameter) holds a set of possible states (bit set, so the join at
 * a merge point is the union, which is the spec's `Maybe(a, b)`):
 *   O owned (obligation open)      N null            F freed        M moved
 *   B borrowed alias / borrowed parameter            X "dead but already reported": usable, no obligation
 * Aliases additionally know the owner slots they borrow from ([Slot.refs]); [Slot.sites] are the
 * allocation sites of the value(s) the slot owns (for leak messages and allocator checks).
 */

internal const val O = 1
internal const val N = 2
internal const val F = 4
internal const val M = 8
internal const val B = 16
internal const val X = 32

/** Kind of an allocation site; [obligation]: the value must be released or handed on. */
internal enum class SK(val obligation: Boolean, val label: String) {
    Heap(true, "Heap"), Pool(true, "Pool"), Debug(true, "DebugAllocator"), Resource(true, "resource"),
    Param(true, "@Owned parameter"), Field(true, "value moved out of a field"),
    /** Owned result of a call whose allocator is not visible at the call (obligation when bound, no temp-leak report). */
    Unknown(true, "owned value"),
    Arena(false, "Arena"), Stack(false, "stack"),
}

internal data class Site(val pos: Pos, val kind: SK, val id: Int)

internal data class Slot(val bits: Int, val refs: Set<Int>, val sites: Set<Site>, val vpos: Pos) {
    fun join(o: Slot) = Slot(bits or o.bits, refs + o.refs, sites + o.sites, if (vpos <= o.vpos) vpos else o.vpos)
}

/** Field state of `local.field` (one level): bits U unknown, N null/empty, M moved out, O owned content. */
internal data class FieldSt(val bits: Int, val movePos: Set<Pos>) {
    fun join(o: FieldSt) = FieldSt(bits or o.bits, movePos + o.movePos)
}

internal const val FU = 1
internal const val FN = 2
internal const val FM = 4
internal const val FO = 8

internal data class FKey(val slot: Int, val field: String)

internal class State(val slots: Map<Int, Slot>, val fields: Map<FKey, FieldSt>) {
    fun withSlot(id: Int, s: Slot): State {
        val m = HashMap(slots); m[id] = s
        return State(m, fields)
    }

    fun withField(k: FKey, f: FieldSt?): State {
        val m = HashMap(fields)
        if (f == null) m.remove(k) else m[k] = f
        return State(slots, m)
    }

    fun withoutFieldsOf(slot: Int): State {
        if (fields.keys.none { it.slot == slot }) return this
        return State(slots, fields.filterKeys { it.slot != slot })
    }

    fun join(o: State): State {
        val s = HashMap(slots)
        for ((k, v) in o.slots) s[k] = s[k]?.join(v) ?: v
        val f = HashMap<FKey, FieldSt>()
        val keys = fields.keys + o.fields.keys
        for (k in keys) {
            val a = fields[k] ?: FieldSt(FU, emptySet())
            val b = o.fields[k] ?: FieldSt(FU, emptySet())
            f[k] = a.join(b)
        }
        return State(s, f)
    }

    override fun equals(other: Any?): Boolean = other is State && slots == other.slots && fields == other.fields
    override fun hashCode(): Int = slots.hashCode() * 31 + fields.hashCode()

    companion object {
        val EMPTY = State(emptyMap(), emptyMap())
    }
}

/** Abstract value of an expression. */
internal class AVal(
    val type: MType,
    val fresh: Boolean,
    /** Slot read directly (for moves / aliasing), or -1. */
    val src: Int,
    /** Owner (root) slots this value borrows from. */
    val refs: Set<Int>,
    val sites: Set<Site>,
    val nullable: Boolean,
    val isNull: Boolean,
    val pos: Pos,
    val strLit: Boolean = false,
    /** Field states of a freshly constructed object. */
    val initFields: Map<String, Int>? = null,
) {
    val tracked: Boolean get() = type.tracked

    companion object {
        fun untracked(type: MType, pos: Pos, strLit: Boolean = false) =
            AVal(type, false, -1, emptySet(), emptySet(), false, false, pos, strLit)
    }
}
