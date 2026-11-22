// REJECT M0101: the element removeAt returned is an owner: freeing it and using it is a use after free
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = mutableListOf(Item(1), Item(2))
    val gone = ys.removeAt(0)
    gone.free()
    println(gone.id)
}
