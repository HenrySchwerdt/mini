// REJECT M0110: a Strict value is stored into a Managed map
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    val b = Box(1)
    m[1] = b
    println(m.size)
    b.free()
}
