// REJECT M0101: an object of the default allocator is freed explicitly and then used
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun make(): Box = Box(1)

fun main() {
    val b = make()
    b.free()
    println(b.v)
}
