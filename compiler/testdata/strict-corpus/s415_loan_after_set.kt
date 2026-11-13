// REJECT M0101: `ys[0] = x` replaces an element: a loan of the old one is over
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = mutableListOf(Item(1))
    val first = ys[0]
    ys[0] = Item(2)
    println(first.id)
}
