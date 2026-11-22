// REJECT M0104: the owner of an object of the default allocator was moved into an owning field
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)
class Holder(val item: Box)

fun main() {
    val b = Box(1)
    val h = Holder(b)
    println(b.v)
    println(h.item.v)
}
