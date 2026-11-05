// ACCEPT: a local lambda that receives the object as a parameter captures nothing
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(4)
    val f = { x: Int, y: Box -> x + y.v }
    println(f(1, b))
    println(f(2, b))
    b.free()
}
