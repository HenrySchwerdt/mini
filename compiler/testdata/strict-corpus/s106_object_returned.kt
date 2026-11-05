// REJECT M0112: an anonymous object that borrows an owned object is returned
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun read(): Int }

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun make(): Reader {
    val b = Box(7)
    val r = object : Reader { override fun read(): Int = b.v }
    return r
}

fun main() {
    println(make().read())
}
