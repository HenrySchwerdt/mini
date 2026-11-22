// ACCEPT: a collection of default-allocated objects built in an inner scope and assigned to a variable of an outer scope (also through an if expression) lives on through that variable
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val v: Int)

fun item(n: Int): Item = Item(n)

fun total(l: List<Item>): Int {
    var s = 0
    for (x in l) s += x.v
    return s
}

fun pick(k: Boolean): Int {
    var b: List<Item> = emptyList()
    if (k) {
        b = if (k) listOf(item(1), item(2)) else emptyList()
    }
    return total(b)
}

fun pick2(k: Boolean): Int {
    var b: List<Item> = emptyList()
    if (k) {
        val t = listOf(item(3), item(4))
        b = t
    }
    return total(b)
}

fun main() {
    println(pick(true))
    println(pick(false))
    println(pick2(true))
    println(pick2(false))
}
