// REJECT M0104: an owner of the default allocator is moved on the first iteration and used again on the next one
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun consume(@Owned b: Box) {
    b.free()
}

fun main() {
    val b = Box(1)
    for (i in 1..2) {
        consume(b)
    }
}
