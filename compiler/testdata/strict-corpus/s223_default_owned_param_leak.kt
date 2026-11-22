// REJECT M0102: an `@Owned` parameter keeps its explicit obligation even when the object came from the default allocator
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun take(@Owned b: Box) {
    println(b.v)
}

fun main() {
    take(Box(1))
}
