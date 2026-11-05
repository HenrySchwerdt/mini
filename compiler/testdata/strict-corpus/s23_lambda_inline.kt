// ACCEPT: a non-escaping lambda captures a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(2)
    val xs = listOf(1, 2, 3)
    println(xs.map { it * b.v })
    xs.forEach { println(it + b.v) }
    repeat(2) { println(b.v) }
    b.free()
}
