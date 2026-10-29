@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Vec(val x: Int, val y: Int)

@NoAlloc
fun dist2(a: Int, b: Int): Int {
    val v = Vec(a, b)          // does not escape: lives on the stack, allowed
    return v.x * v.x + v.y * v.y
}

fun main() {
    println(dist2(1, 2))
}
