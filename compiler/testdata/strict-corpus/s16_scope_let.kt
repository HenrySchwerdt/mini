// ACCEPT: let / also / apply / run / with on a Strict object borrow it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(1)
    val r = b.let { it.v + 1 }
    b.also { println(it.v) }
    b.apply { v = 10 }
    val s = b.run { v + r }
    val t = with(b) { v * 2 }
    println(r)
    println(s)
    println(t)
    b.free()
}
