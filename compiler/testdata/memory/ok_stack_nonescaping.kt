// ACCEPT: non-escaping objects live on the stack, no allocator and no free needed. Spec 2.1
@file:MemoryMode(Mode.Strict)

package memory.ok_stack_nonescaping

import mini.memory.*

class Vec(val x: Int, val y: Int)

fun len2(v: Vec): Int = v.x * v.x + v.y * v.y

fun main() {
    val v = Vec(3, 4)
    println(len2(v))
}
