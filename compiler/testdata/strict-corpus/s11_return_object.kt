// ACCEPT: returning a fresh object transfers ownership to the caller
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun make(v: Int): Box = Box(v)

fun main() {
    val b = make(3)
    println(b.v)
    b.free()
}
