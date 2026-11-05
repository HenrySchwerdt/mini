// ACCEPT: an arena owns a whole object graph
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Node(val v: Int, var next: Node?)

context(a: Arena)
fun build(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)
    return head
}

fun sum(n: Node?): Int {
    var s = 0
    var c = n
    while (c != null) { s += c.v; c = c.next }
    return s
}

fun main() {
    Arena().use { a ->
        context(a) {
            val l = build(4)
            println(sum(l))
        }
    }
}
