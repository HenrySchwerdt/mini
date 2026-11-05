// REJECT M0110: a Strict object stored into a field of a Managed class
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

@MemoryMode(Mode.Managed)
class Registry { var last: Box? = null }

fun main() {
    val r = Registry()
    val b = Box(1)
    r.last = b
    b.free()
}
