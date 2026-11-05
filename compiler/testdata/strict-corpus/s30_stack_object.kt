// ACCEPT: a non-escaping object without allocator lives on the stack; no free needed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

data class P(val x: Int, val y: Int)

fun len2(p: P): Int = p.x * p.x + p.y * p.y

fun main() {
    val p = P(3, 4)
    println(p)
    println(len2(p))
    println("p=$p")
}
