// ACCEPT: a lambda value called before the owner it captures is freed borrows the owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(4)
    val f = { x: Int -> x + b.v }
    println(f(1))
    b.free()
}
