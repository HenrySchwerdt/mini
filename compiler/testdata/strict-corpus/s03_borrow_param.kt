// ACCEPT: an unannotated parameter is borrowed; the caller keeps the obligation
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun twice(b: Box): Int = b.v * 2

fun main() {
    val b = Box(21)
    println(twice(b))
    println(twice(b))
    b.free()
}
