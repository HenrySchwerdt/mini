// REJECT M0101: removing an element ends the loans of the elements of the list
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    val first = xs[0]
    xs.removeAt(1)
    println(first.v)
}

fun main() {
    f(mutableListOf())
}
