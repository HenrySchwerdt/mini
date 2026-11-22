// REJECT M0101: a borrowed alias of an owner of the default allocator used after the owner was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun peek(b: Box): @Borrowed Box = b

fun main() {
    val b = Box(1)
    val p = peek(b)
    println(p.v)
    b.free()
    println(p.v)
}
