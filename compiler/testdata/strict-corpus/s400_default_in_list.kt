// ACCEPT: default-allocated objects moved into a list and a vararg: the list holds its own count of each element, the owners keep theirs and release them when they end
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun total(xs: List<Item>): Int {
    var s = 0
    for (x in xs) s += x.id
    return s
}

fun main() {
    val ys = mutableListOf(Item(1), Item(2))
    ys.add(Item(3))
    val b = Item(4)
    ys.add(b)
    println(ys.size)
    println(total(ys))
    val first = ys[0]
    println(first.id)
    ys.forEach { println(it.id) }
    val zs = listOf(Item(10), Item(20))
    println(zs.size + total(zs))
}
