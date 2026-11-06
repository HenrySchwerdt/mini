package mini.backend

/*
 * Object layout: the ONE function both backends use (CEmitter emits a C struct in this order and asserts the offsets,
 * the LLVM backends use the offsets directly), so the emitted code agrees byte for byte. See docs/memory-model.md,
 * "Object layout".
 *
 * Rules (deterministic in the class chain alone: a class's layout depends only on its own fields and on its superclass's
 * layout, never on the program that uses it, so precompiled libraries and the programs compiled against them agree):
 *   1. The header comes first; a class defined by the runtime (an exception class: header, message, cause) is a fixed prefix.
 *   2. A class's own fields are placed after the superclass's *unpadded* data end (not after its tail padding), so the
 *      subclass fills the superclass's tail padding.
 *   3. Own fields are placed largest alignment first, then largest size, then declaration order; whenever the next
 *      aligned slot would leave a gap, the largest remaining field that fits the gap goes in first.
 *   4. size = data end rounded up to the object alignment (the largest field alignment of the whole chain).
 */

/** One field slot of a class struct: the class that declares the field, its name, type and byte offset. */
class LayoutSlot(val owner: String, val name: String, val type: MirType, val offset: Int, val size: Int)

/**
 * Size, alignment and field offsets of one class struct. [dataEnd] is the end of the last field (the size without tail padding).
 * [slots] lists every field of the whole chain (inherited ones included, runtime-defined prefix excluded) by ascending offset;
 * [offsets] only the fields the class declares itself.
 */
class ClassLayout(val size: Int, val align: Int, val offsets: HashMap<String, Int>, val dataEnd: Int, val slots: List<LayoutSlot>)

class ObjectLayouts(private val classes: Map<String, MirClass>, private val ptrBytes: Int) {
    private val memo = HashMap<String, ClassLayout>()

    /** `struct MkObject`: type pointer, refcount, flags. */
    val headerSize: Int get() = ptrBytes + 8

    fun alignUp(n: Int, a: Int): Int = (n + a - 1) / a * a

    fun sizeOf(t: MirType): Int = when {
        t is TInline -> sizeOf(t.rep)
        t === TInt -> 4
        t === TLong || t === TDouble -> 8
        t === TBoolean -> 1
        t === TChar || t === TShort -> 2
        t === TByte -> 1
        t === TFloat -> 4
        t is TPtr -> ptrBytes
        t.isRef -> ptrBytes
        else -> throw EmitError("no size for $t")
    }

    fun alignOf(t: MirType): Int = if (t === TLong || t === TDouble) 8 else sizeOf(t)

    fun of(cName: String): ClassLayout = compute(cName)

    /** The layout under the pre-optimisation rules (declaration order, the superclass struct embedded with its tail padding). */
    fun naive(cName: String): ClassLayout = computeNaive(cName)

    private val naiveMemo = HashMap<String, ClassLayout>()

    private fun computeNaive(cName: String): ClassLayout {
        val known = naiveMemo[cName]
        if (known != null) return known
        val c = classes[cName] ?: throw EmitError("unknown class $cName")
        val base: ClassLayout? = if (c.superCName != null) computeNaive(c.superCName) else null
        var cur: Int
        var align: Int
        val offsets = HashMap<String, Int>()
        if (base != null) {
            cur = base.size
            align = base.align
        } else {
            cur = headerSize
            align = ptrBytes
            if (c.external) {
                offsets["message"] = cur
                cur += ptrBytes
                offsets["cause"] = cur
                cur += ptrBytes
            }
        }
        if (!c.external) {
            for (f in c.fields) {
                val a = alignOf(f.type)
                cur = alignUp(cur, a)
                offsets[f.name] = cur
                cur += sizeOf(f.type)
                if (a > align) align = a
            }
        }
        val l = ClassLayout(alignUp(cur, align), align, offsets, cur, emptyList())
        naiveMemo[cName] = l
        return l
    }

    private fun compute(cName: String): ClassLayout {
        val known = memo[cName]
        if (known != null) return known
        val c = classes[cName] ?: throw EmitError("unknown class $cName")
        val base: ClassLayout? = if (c.superCName != null) of(c.superCName) else null
        var cur: Int
        var align: Int
        val offsets = HashMap<String, Int>()
        val slots = ArrayList<LayoutSlot>()
        if (base != null) {
            cur = base.dataEnd
            align = base.align
            slots.addAll(base.slots)
        } else {
            cur = headerSize
            align = ptrBytes
            if (c.external) {
                // the built-in exception classes: MkThrowable { header, message, cause }
                offsets["message"] = cur
                cur += ptrBytes
                offsets["cause"] = cur
                cur += ptrBytes
            }
        }
        if (!c.external) {
            val rest = ArrayList<MirField>(c.fields)
            // largest alignment first, then largest size; the sort is stable (declaration order breaks ties)
            val sorted = rest.sortedBy { -(alignOf(it.type) * 16 + sizeOf(it.type)) }
            val pending = ArrayList<MirField>(sorted)
            while (pending.isNotEmpty()) {
                // the first (largest) pending field whose alignment divides the current offset
                var pick = -1
                for (i in pending.indices) {
                    if (cur % alignOf(pending[i].type) == 0) {
                        pick = i
                        break
                    }
                }
                if (pick < 0) {
                    // nothing fits at this offset: advance to the next boundary of the smallest pending alignment
                    var minA = alignOf(pending[0].type)
                    for (f in pending) if (alignOf(f.type) < minA) minA = alignOf(f.type)
                    cur = alignUp(cur, minA)
                    continue
                }
                val f = pending.removeAt(pick)
                val a = alignOf(f.type)
                offsets[f.name] = cur
                slots.add(LayoutSlot(cName, f.name, f.type, cur, sizeOf(f.type)))
                cur += sizeOf(f.type)
                if (a > align) align = a
            }
        }
        val l = ClassLayout(alignUp(cur, align), align, offsets, cur, slots.sortedBy { it.offset })
        memo[cName] = l
        return l
    }

    /** Byte offset of field [field] declared by class [owner]. */
    fun fieldOffset(owner: String, field: String): Int {
        if (owner == ANY_CNAME) throw EmitError("field $field of kotlin.Any")
        return of(owner).offsets[field] ?: throw EmitError("unknown field $owner.$field")
    }
}
