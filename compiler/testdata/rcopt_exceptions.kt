// RC elision (RcOpt) on exception paths: borrowed locals are not released when an exception leaves the
// scope, owned ones still are, and a local whose reference was moved is cleared before anything can throw.
// Runs under ASan/UBSan with the leak check, so a double release or a missing release is reported.

class Node(var value: Int, var next: Node?)
class Holder(var items: ArrayList<Int>, var node: Node?)

fun risky(n: Int): Int {
    if (n > 2) throw IllegalStateException("boom " + n)
    return n
}

// alias of a field live across calls that throw, caught inside the loop
fun aliasAcrossThrow(h: Holder): Int {
    val items = h.items
    var total = 0
    for (i in 0 until 5) {
        try {
            total += risky(i) + items.size
        } catch (e: IllegalStateException) {
            total += 100 + items.size
        }
    }
    return total
}

// the exception leaves the function while an alias and an owned local exist
fun aliasAndOwnedUnwind(h: Holder, n: Int): Int {
    val items = h.items
    val owned = Node(n, null)
    return risky(owned.value) + items.size
}

fun callsUnwind(h: Holder): String {
    return try {
        "ok " + aliasAndOwnedUnwind(h, 1)
    } catch (e: IllegalStateException) {
        "caught " + e.message
    }
}

fun callsUnwind2(h: Holder): String {
    return try {
        "ok " + aliasAndOwnedUnwind(h, 7)
    } catch (e: IllegalStateException) {
        "caught " + e.message
    }
}

// the reference moves into a field, afterwards something throws
fun moveThenThrow(head: Node, v: Int): Int {
    val n = Node(v, null)
    head.next = n
    return risky(v)
}

fun moveThenThrowCaught(head: Node, v: Int): String {
    return try {
        "ok " + moveThenThrow(head, v)
    } catch (e: IllegalStateException) {
        "caught " + e.message + " next=" + head.next!!.value
    }
}

// the throw happens while the value is still owned (cast fails before the move)
fun castFailsOnOwned(): Int {
    val any: Any = Node(1, null)
    return try {
        val s = any as String
        s.length
    } catch (e: ClassCastException) {
        -2
    }
}

fun finallyPaths(h: Holder, fail: Boolean): Int {
    val items = h.items
    var r = 0
    try {
        if (fail) risky(10)
        r = items.size
    } catch (e: IllegalStateException) {
        r = -items.size
    } finally {
        r += 1000
    }
    return r
}

// an exception propagating through several frames that each hold borrowed and owned references
fun level3(h: Holder, n: Int): Int {
    val node = h.node
    val local = Node(n, node)
    return risky(local.value) + (node?.value ?: 0)
}

fun level2(h: Holder, n: Int): Int {
    val items = h.items
    val fresh = Node(n, null)
    return level3(h, n) + items.size + fresh.value
}

fun level1(h: Holder, n: Int): String {
    return try {
        "v=" + level2(h, n)
    } catch (e: IllegalStateException) {
        "unwound " + e.message
    }
}

fun main() {
    val h = Holder(arrayListOf(1, 2, 3), Node(5, null))
    println(aliasAcrossThrow(h))
    println(callsUnwind(h))
    println(callsUnwind2(h))
    val head = Node(0, null)
    println(moveThenThrowCaught(head, 1))
    println(moveThenThrowCaught(head, 9))
    println(castFailsOnOwned())
    println(finallyPaths(h, false))
    println(finallyPaths(h, true))
    println(level1(h, 1))
    println(level1(h, 4))
}
