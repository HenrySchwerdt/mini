// REJECT M0101: an anonymous object that borrows an owned object is used after the owner was freed
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
    println(r.read())
}
