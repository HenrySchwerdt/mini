// REJECT M0101: a closure that borrows an owned object is called after the object was freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(4)
    val f = { x: Int -> x + b.v }
    println(f(1))
    b.free()
    println(f(2))
}
