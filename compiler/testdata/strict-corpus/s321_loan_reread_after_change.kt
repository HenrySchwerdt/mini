// ACCEPT: the element is read again after the change: the new loan is valid
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>): Int {
    if (xs.isEmpty()) return 0
    var first = xs[0]
    val a = first.v
    xs.clear()
    if (xs.isNotEmpty()) {
        first = xs[0]
    }
    return a
}

fun main() {
    println(f(mutableListOf()))
}
