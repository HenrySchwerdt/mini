// ACCEPT: an unannotated Managed library function that only reads its argument (inferred from its body)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

@MemoryMode(Mode.Managed)
object Lib {
    fun describe(b: Box): String = "Box " + b.v
    fun twice(b: Box): String = describe(b) + describe(b)
}

fun main() {
    val b = Box(1)
    println(Lib.twice(b))
    b.free()
}
