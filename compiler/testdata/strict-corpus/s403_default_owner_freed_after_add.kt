// ACCEPT: the owner may free its object after adding it: the list keeps its own count of the reference counted object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    val ys = mutableListOf<Item>()
    val b = Item(5)
    ys.add(b)
    b.free()
    println(ys[0].id)
    println(ys.size)
}
