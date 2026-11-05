// REJECT M0101: used after free
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(5)
    b.free()
    println(b.v)
}
