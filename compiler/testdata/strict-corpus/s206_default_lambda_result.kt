// ACCEPT: a lambda may return the allocation itself (the result is a counted reference for its caller), also through map
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val f = { Box(1) }
    println(f().v)
    val g: (Int) -> Box = { n -> Box(n * 3) }
    println(g(4).v)
}
