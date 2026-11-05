// ACCEPT: by lazy over values read from a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(4)
    val v = b.v
    val d by lazy { v * 2 }
    println(d)
    b.free()
}
