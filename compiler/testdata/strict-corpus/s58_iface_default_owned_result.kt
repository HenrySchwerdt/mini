// ACCEPT: an interface factory returning an owned object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Factory { fun make(): Box }
@AllocatedBy(Heap::class)
class Box(val v: Int)
class F : Factory { override fun make(): Box = Box(8) }

fun main() {
    val f: Factory = F()
    val b = f.make()
    println(b.v)
    b.free()
}
