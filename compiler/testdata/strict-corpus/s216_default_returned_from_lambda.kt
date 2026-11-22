// REJECT M0204: an owner of the default allocator is released when its lambda ends, so it cannot be the lambda's result
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(var v: Int)

fun main() {
    val f: () -> Box = {
        val b = Box(1)
        b.v = 2
        b
    }
    println(f().v)
}
