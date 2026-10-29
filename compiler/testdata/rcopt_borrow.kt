// RC elision (RcOpt): borrowed aliases of parameters, owned locals and fields. The "hazard" functions
// overwrite the field an alias was read from while the alias is still needed: a wrong elision is a
// use-after-free that the ASan run of the leak check reports.

class Node(var value: Int, var next: Node?)
class Holder(var items: ArrayList<Int>, var node: Node?)

interface Resetter {
    fun reset(h: Holder)
}

class FreshList : Resetter {
    override fun reset(h: Holder) {
        h.items = ArrayList<Int>()
    }
}

class Nothing2 : Resetter {
    override fun reset(h: Holder) {}
}

class ResetOnConstruction(h: Holder) {
    val marker = 7
    init {
        h.items = ArrayList<Int>()
    }
}

fun sumList(l: ArrayList<Int>): Int {
    var s = 0
    for (x in l) s += x
    return s
}

// alias of a parameter and of a field, only pure calls in between: no reference counting needed
fun readField(h: Holder): Int {
    val items = h.items
    return items.size + sumList(items)
}

fun replaceItems(h: Holder, fresh: ArrayList<Int>) {
    h.items = fresh
}

// the callee overwrites h.items: the old list must stay alive for the caller
fun hazardDirectStore(h: Holder): Int {
    val old = h.items
    h.items = ArrayList<Int>()
    return old.size
}

fun hazardCall(h: Holder): Int {
    val old = h.items
    replaceItems(h, ArrayList<Int>())
    return old.size + sumList(old)
}

fun hazardVirtual(h: Holder, r: Resetter): Int {
    val old = h.items
    r.reset(h)
    return old.size
}

fun hazardConstructor(h: Holder): Int {
    val old = h.items
    val r = ResetOnConstruction(h)
    return old.size + r.marker
}

fun hazardLambda(h: Holder, f: () -> Unit): Int {
    val old = h.items
    f()
    return old.size
}

// the call receiving the alias itself overwrites the field it came from
fun clearHolder(h: Holder, items: ArrayList<Int>): Int {
    h.items = ArrayList<Int>()
    return items.size
}

fun hazardReceiver(h: Holder): Int {
    val items = h.items
    return clearHolder(h, items)
}

// a chain of field reads
fun chain(h: Holder): Int {
    val n = h.node
    if (n == null) return -1
    val m = n.next
    if (m == null) return n.value
    return n.value * 10 + m.value
}

fun hazardChain(h: Holder): Int {
    val n = h.node
    h.node = null
    if (n == null) return -1
    val m = n.next
    n.next = null
    return if (m == null) n.value else n.value + m.value
}

// the root local is reassigned while an alias of one of its fields is alive
fun rootReassigned(): Int {
    var n = Node(1, Node(2, null))
    val inner = n.next
    n = Node(3, null)
    return n.value * 100 + (inner?.value ?: 0)
}

// traversal variable: borrows when the loop body does not mutate, owns otherwise
fun length(n: Node?): Int {
    var c = 0
    var p = n
    while (p != null) {
        c++
        p = p.next
    }
    return c
}

fun truncateWhileWalking(n: Node?): Int {
    var c = 0
    var p = n
    while (p != null) {
        c++
        val next = p.next
        p.next = null
        p = next
    }
    return c
}

// the first access of an object runs its initialiser, which overwrites the field the alias came from
val globalHolder = Holder(arrayListOf(1, 2, 3, 4, 5), null)

object LazyReset {
    val marker = 11
    init {
        globalHolder.items = ArrayList<Int>()
    }
}

fun hazardObjectInit(): Int {
    val h = globalHolder
    val old = h.items
    val m = LazyReset.marker
    return old.size + m
}

// Hazards with precise uses: the alias is only read through plain field reads (no call whose own effects could
// make the elision decision for it), so each function fails under ASan when exactly its own rule is missing.
fun replaceNode(h: Holder) {
    h.node = Node(100, null)
}

interface NodeResetter {
    fun reset(h: Holder)
}

class NodeReset : NodeResetter {
    override fun reset(h: Holder) {
        h.node = Node(200, null)
    }
}

open class BaseReset {
    open fun reset(h: Holder) {}
}

class NodeReset2 : BaseReset() {
    override fun reset(h: Holder) {
        h.node = Node(400, null)
    }
}

fun preciseVCall(h: Holder, r: BaseReset): Int {
    val old = h.node
    r.reset(h)
    return old!!.value
}

class NodeResetOnConstruction(h: Holder) {
    init {
        h.node = Node(300, null)
    }
}

fun preciseDirect(h: Holder): Int {
    val old = h.node
    h.node = Node(1, null)
    return old!!.value
}

fun preciseCall(h: Holder): Int {
    val old = h.node
    replaceNode(h)
    return old!!.value
}

fun preciseVirtual(h: Holder, r: NodeResetter): Int {
    val old = h.node
    r.reset(h)
    return old!!.value
}

fun preciseLambda(h: Holder, f: () -> Unit): Int {
    val old = h.node
    f()
    return old!!.value
}

fun preciseCtor(h: Holder): Int {
    val old = h.node
    NodeResetOnConstruction(h)
    return old!!.value
}

fun readAfterStore(h: Holder, old: Node?): Int {
    h.node = null
    return old!!.value
}

fun preciseReceiver(h: Holder): Int {
    val old = h.node
    return readAfterStore(h, old)
}

val globalHolder2 = Holder(ArrayList<Int>(), Node(77, null))

object LazyReset2 {
    val marker = 5
    init {
        globalHolder2.node = Node(0, null)
    }
}

fun preciseObjectInit(): Int {
    val h = globalHolder2
    val old = h.node
    val m = LazyReset2.marker
    return old!!.value + m
}

fun main() {
    val h = Holder(arrayListOf(1, 2, 3), Node(5, Node(6, null)))
    println(readField(h))
    println(hazardDirectStore(h))
    h.items = arrayListOf(4, 5)
    println(hazardCall(h))
    h.items = arrayListOf(4, 5, 6)
    println(hazardVirtual(h, FreshList()))
    h.items = arrayListOf(1)
    println(hazardVirtual(h, Nothing2()))
    println(hazardConstructor(h))
    h.items = arrayListOf(9, 9)
    println(hazardLambda(h) { h.items = ArrayList<Int>() })
    h.items = arrayListOf(1, 2, 3, 4)
    println(hazardReceiver(h))
    println(chain(h))
    println(hazardChain(h))
    println(chain(h))
    println(rootReassigned())
    val list = Node(1, Node(2, Node(3, null)))
    println(length(list))
    println(truncateWhileWalking(list))
    println(length(list))
    println(hazardObjectInit())
    val h2 = Holder(ArrayList<Int>(), Node(11, null))
    println(preciseDirect(h2))
    h2.node = Node(12, null)
    println(preciseCall(h2))
    h2.node = Node(13, null)
    println(preciseVirtual(h2, NodeReset()))
    h2.node = Node(14, null)
    println(preciseLambda(h2) { h2.node = Node(1, null) })
    h2.node = Node(15, null)
    println(preciseCtor(h2))
    h2.node = Node(16, null)
    println(preciseReceiver(h2))
    h2.node = Node(17, null)
    println(preciseVCall(h2, NodeReset2()))
    println(preciseObjectInit())
}
