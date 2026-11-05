// REJECT M0110: handing an owning object to a collection is not supported
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val xs = mutableListOf<Box>()
    val b = Box(1)
    xs.add(b)
    println(xs.size)
    b.free()
}
