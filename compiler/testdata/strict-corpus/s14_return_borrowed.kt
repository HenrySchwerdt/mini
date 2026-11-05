// REJECT M0109: a borrowed parameter returned as an owned result
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun same(b: Box): Box = b

fun main() {
    val b = Box(1)
    val c = same(b)
    c.free()
    b.free()
}
