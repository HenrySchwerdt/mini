// ACCEPT: what is needed from an element is copied out before the list changes
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>): Int {
    if (xs.isEmpty()) return 0
    val v = xs[0].v
    xs.clear()
    return v
}

fun g(xs: List<Box>): Int {
    var s = 0
    for (b in xs) s += b.v
    return s
}

fun main() {
    println(f(mutableListOf()))
    println(g(emptyList()))
}
