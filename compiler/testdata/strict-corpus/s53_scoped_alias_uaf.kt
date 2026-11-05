// REJECT M0101: a loan used after its owner was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val a = Box(1)
    val b = a
    a.free()
    println(b.v)
}
