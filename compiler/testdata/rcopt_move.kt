// RC elision (RcOpt): moves. A local whose last use hands its reference to a field, another local or the
// return value gives the reference away instead of retaining a second one. The cases where the local is
// still used afterwards (or on the other path of a branch, or in a later loop iteration) must keep the retain.

class Node(var value: Int, var next: Node?)
class Box(var content: Any?)

fun <T> identity(x: T): T = x

fun makeString(i: Int): String = identity("s" + i)

fun makeNode(v: Int): Node {
    val n = Node(v, null)
    return n
}

fun makeBoxed(v: Int): Any? {
    val b = Box(v)
    return b.content
}

// Declare / SetField moves
fun attach(head: Node, v: Int) {
    val n = Node(v, null)
    head.next = n
}

// the local is used after the store: the store must retain
fun attachAndRead(head: Node, v: Int): Int {
    val n = Node(v, null)
    head.next = n
    n.value = n.value + 1
    return n.value
}

// move on one path, use on the other
fun branch(head: Node, flag: Boolean): Int {
    val n = Node(10, null)
    if (flag) {
        head.next = n
    } else {
        n.value = 20
        return n.value
    }
    return head.next!!.value
}

// a move inside a loop: the local is declared in the loop body
fun buildList(n: Int): Node? {
    var head: Node? = null
    for (i in 0 until n) {
        val nn = Node(i, head)
        head = nn
    }
    return head
}

// local declared outside the loop, stored inside: not a last use
fun storeManyTimes(h: Box, n: Int): Int {
    val shared = Node(99, null)
    var count = 0
    for (i in 0 until n) {
        h.content = shared
        count++
    }
    return count + shared.value
}

// Assign moves between locals
fun swapChain(a: Node): Node {
    var cur = a
    val t = Node(1, cur)
    cur = t
    return cur
}

// checked cast of an owned local in return position
fun castReturn(): Node {
    val any: Any = Node(42, null)
    return any as Node
}

fun failingCast(): Int {
    val any: Any = "text"
    return try {
        val n = any as Node
        n.value
    } catch (e: ClassCastException) {
        -1
    }
}

fun sum(n: Node?): Int {
    var s = 0
    var p = n
    while (p != null) {
        s += p.value
        p = p.next
    }
    return s
}

fun main() {
    println(makeString(7))
    println(makeNode(3).value)
    println(makeBoxed(5))
    val head = Node(0, null)
    attach(head, 4)
    println(head.next!!.value)
    println(attachAndRead(head, 8))
    println(head.next!!.value)
    println(branch(Node(1, null), true))
    println(branch(Node(1, null), false))
    println(sum(buildList(10)))
    val b = Box(null)
    println(storeManyTimes(b, 5))
    println((b.content as Node).value)
    println(swapChain(Node(5, null)).next!!.value)
    println(castReturn().value)
    println(failingCast())
}
