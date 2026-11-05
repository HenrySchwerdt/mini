// ACCEPT: to / Pair of Managed values, Strict value used next to them
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(1)
    val p = "k" to b.v
    println(p)
    b.free()
}
