// ACCEPT: a @NoAlloc function may use stack objects. Spec 8
@file:MemoryMode(Mode.Strict)

package memory.ok_noalloc

import mini.memory.*

class Vec(val x: Int, val y: Int)

@NoAlloc
fun dist2(a: Int, b: Int): Int {
    val v = Vec(a, b)
    return v.x * v.x + v.y * v.y
}

fun main() {
    println(dist2(1, 2))
}
