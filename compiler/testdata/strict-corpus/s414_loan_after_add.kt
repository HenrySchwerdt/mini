// REJECT M0101: every mutating call ends the loans of the elements, add included
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = mutableListOf(Item(1))
    val first = ys[0]
    ys.add(Item(2))
    println(first.id)
}
