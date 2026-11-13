// REJECT M0101: the default-allocated twin of s147: a default-allocated owner added to a list is accepted, and the element read back is a loan that clear() ends
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = mutableListOf<Item>()
    val b = Item(5)
    ys.add(b)
    val first = ys[0]
    ys.clear()
    println(first.id)
}
