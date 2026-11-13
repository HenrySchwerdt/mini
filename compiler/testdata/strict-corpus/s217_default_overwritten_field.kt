// REJECT M0102: an owning field holds an object of the default allocator; assigning a new one loses the old value
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)
class Holder(var item: Box?)

fun fill(h: Holder) {
    h.item = Box(1)
}

fun main() {
    val h = Holder(null)
    fill(h)
    println(h.item?.v)
}
