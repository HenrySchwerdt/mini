// REJECT M0109: a borrowed parameter is stored into an owning field
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

class Holder(var item: Box?)

fun keep(h: Holder, b: Box) {
    h.item = b
}

fun main() {
    val h = Holder(null)
    val b = Box(1)
    keep(h, b)
    h.free()
}
