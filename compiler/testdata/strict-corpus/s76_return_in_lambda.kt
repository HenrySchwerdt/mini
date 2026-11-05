// ACCEPT: early return from inline lambdas (forEach) around a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun firstAbove(xs: List<Int>, b: Box): Int {
    xs.forEach { if (it > b.v) return it }
    return -1
}

fun main() {
    val b = Box(2)
    println(firstAbove(listOf(1, 2, 3), b))
    b.free()
}
