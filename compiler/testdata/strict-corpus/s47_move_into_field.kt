// ACCEPT: moving an object into an owning field; the container frees it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

@AllocatedBy(Heap::class)
class Holder(var item: Box?)

fun main() {
    val h = Holder(null)
    val b = Box(4)
    h.item = b.move()
    println(h.item?.v)
    h.free()
}
