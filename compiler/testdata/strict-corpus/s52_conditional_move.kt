// REJECT M0104: moved on one path, then used
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun consume(b: @Owned Box) { b.free() }

fun main() {
    val b = Box(1)
    if (b.v > 0) consume(b)
    println(b.v)
    b.free()
}
