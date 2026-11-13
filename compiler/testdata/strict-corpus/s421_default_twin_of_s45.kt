// ACCEPT: the twin of s45: an owner added to a mutable list
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val xs = mutableListOf<Box>()
    val b = Box(1)
    xs.add(b)
    println(xs.size)
    b.free()
    println(xs[0].v)
}
