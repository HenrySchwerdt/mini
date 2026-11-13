// REJECT M0107: a borrowed parameter cannot free an object of the default allocator
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun drop(b: Box) {
    b.free()
}

fun main() {
    val b = Box(1)
    drop(b)
}
