// REJECT M0103: freed twice
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(5)
    b.free()
    b.free()
}
