// REJECT M0102: an allocation of an explicit allocator is still a leak when it is never released
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(1)
    println(b.v)
}
