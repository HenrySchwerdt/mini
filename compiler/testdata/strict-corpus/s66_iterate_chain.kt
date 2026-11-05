// ACCEPT: walking an owned chain with a loan variable
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Node(val v: Int, var next: Node?)

fun main() {
    val head = Node(1, Node(2, Node(3, null)))
    var cur: Node? = head
    var s = 0
    while (cur != null) {
        s += cur.v
        cur = cur.next
    }
    println(s)
    head.free()
}
