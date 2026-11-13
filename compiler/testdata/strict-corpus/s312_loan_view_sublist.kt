// REJECT M0101: a sublist is a view: changing the list ends the loans of its elements
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    val part = xs.subList(0, 1)
    val first = part[0]
    xs.clear()
    println(first.v)
}

fun main() {
    f(mutableListOf())
}
