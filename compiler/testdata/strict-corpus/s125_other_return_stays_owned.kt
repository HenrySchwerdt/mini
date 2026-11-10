// REJECT M0109: only a method that returns nothing but its receiver gets the loan rule; this one can return a parameter as owned
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Node(val v: Int) {
    fun pick(other: Node, first: Boolean): Node {
        if (first) return this
        return other
    }
}

fun main() {
    val a = Node(1)
    val b = Node(2)
    val c = a.pick(b, true)
    c.free()
    a.free()
    b.free()
}
