// REJECT M0103: an object of the default allocator is freed twice
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val b = Box(1)
    println(b.v)
    b.free()
    b.free()
}
