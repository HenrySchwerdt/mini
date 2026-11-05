// REJECT M0112: a local class captures an owned object and the instance escapes
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun read(): Int }

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun make(): Reader {
    val b = Box(7)
    class R : Reader { override fun read(): Int = b.v }
    return R()
}

fun main() {
    println(make().read())
}
