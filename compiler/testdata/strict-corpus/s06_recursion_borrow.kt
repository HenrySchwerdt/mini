// ACCEPT: recursion over a borrowed list node
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Node(val v: Int, val next: Node?)

fun sum(n: Node?): Int = if (n == null) 0 else n.v + sum(n.next)

fun main() {
    val l = Node(1, Node(2, Node(3, null)))
    println(sum(l))
    l.free()
}
