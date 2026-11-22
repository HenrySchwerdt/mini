// ACCEPT: the twin of s79: an owner stored into a map and a set
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    val b = Box(1)
    m[1] = b
    println(m.size)
    val s = hashSetOf<Box>()
    s.add(b)
    println(s.size)
    b.free()
    println(m[1]?.v)
}
