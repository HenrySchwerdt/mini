// ACCEPT: an anonymous object that captures an owned object borrows it while it is used before the free
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun read(): Int }

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(7)
    val r = object : Reader { override fun read(): Int = b.v }
    println(r.read())
    b.free()
}
