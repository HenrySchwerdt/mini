// REJECT M0110: a borrowed parameter may be an object of any allocator: it cannot be added to a list
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun addTo(xs: MutableList<Item>, b: Item) {
    xs.add(b)
}

fun main() {
    val ys = mutableListOf<Item>()
    addTo(ys, Item(1))
}
