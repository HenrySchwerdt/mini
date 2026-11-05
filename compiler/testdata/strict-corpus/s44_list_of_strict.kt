// REJECT M0110: a Kotlin list would keep the Strict objects (listOf stores its arguments in an array)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val a = Box(3)
    val b = Box(1)
    val xs = listOf(a, b)
    println(xs.size)
    println(xs.map { it.v })
    println(xs.sortedBy { it.v }.map { it.v })
    println(xs.filter { it.v > 1 }.size)
    xs.forEach { println(it.v) }
    a.free()
    b.free()
}
