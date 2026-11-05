// ACCEPT: requireNotNull / checkNotNull / !! on nullable Strict values
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val a: Box? = Box(1)
    val b = requireNotNull(a)
    println(b.v)
    println(a!!.v)
    a.free()
}
