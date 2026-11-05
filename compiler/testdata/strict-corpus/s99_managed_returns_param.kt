// REJECT M0110: a Managed function that returns its borrowed argument (the result would be a second owner)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

@MemoryMode(Mode.Managed)
object Lib { fun same(b: Box): Box = b }

fun main() {
    val b = Box(1)
    val c = Lib.same(b)
    println(c.v)
    b.free()
}
