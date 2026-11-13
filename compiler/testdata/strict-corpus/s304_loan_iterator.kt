// REJECT M0101: the element an iterator returned is a loan: `it.remove()` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    val it = xs.iterator()
    val first = it.next()
    it.remove()
    println(first.v)
}

fun main() {
    f(mutableListOf())
}
