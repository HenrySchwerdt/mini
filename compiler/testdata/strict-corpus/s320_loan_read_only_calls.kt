// ACCEPT: between the read of an element and its use the list is only read (size, contains, another element, forEach)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>): Int {
    if (xs.isEmpty()) return 0
    val first = xs[0]
    val n = xs.size
    val second = xs[xs.size - 1]
    var total = 0
    xs.forEach { total += it.v }
    return first.v + n + second.v + total
}

fun main() {
    println(f(mutableListOf()))
}
