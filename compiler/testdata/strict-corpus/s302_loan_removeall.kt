// REJECT M0101: removing elements (`removeAll`) ends the loans of the elements of the list
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>, gone: List<Box>) {
    val first = xs[0]
    xs.removeAll(gone)
    println(first.v)
}

fun main() {
    println(0)
}
