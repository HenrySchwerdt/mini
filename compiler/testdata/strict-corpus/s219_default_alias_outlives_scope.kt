// REJECT M0101: an alias of an owner of the default allocator is used after the owner's block ended (the object is released there)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun peek(b: Box): @Borrowed Box = b

fun f(c: Boolean) {
    var p: Box? = null
    if (c) {
        val b = Box(1)
        p = peek(b)
    }
    println(p?.v)
}

fun main() {
    f(true)
}
