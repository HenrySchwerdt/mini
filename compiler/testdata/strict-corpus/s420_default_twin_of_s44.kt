// ACCEPT: the twin of s44 with a class that has no explicit allocator: the list keeps its own counts, the owners release theirs
@file:MemoryMode(Mode.Strict)

import mini.memory.*

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
