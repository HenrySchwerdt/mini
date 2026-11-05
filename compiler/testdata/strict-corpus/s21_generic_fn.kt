// ACCEPT: a generic function that only reads its argument through a function value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun <T> show(x: T): String = x.toString()

fun main() {
    val b = Box(1)
    println(show(5))
    println(show("a"))
    println(b.v)
    b.free()
}
