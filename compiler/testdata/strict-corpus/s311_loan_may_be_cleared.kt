// REJECT M0101: on one path the list is cleared: the loan may be gone
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>, c: Boolean) {
    val first = xs[0]
    if (c) xs.clear()
    println(first.v)
}

fun main() {
    f(mutableListOf(), false)
}
