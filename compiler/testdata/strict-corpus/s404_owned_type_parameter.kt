// ACCEPT: `@Owned` on a type parameter: the container owns what is stored in a field of that type
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

class Cell<@Owned T>(val item: T)

fun <T> wrap(@Owned x: T): Cell<T> = Cell(x)

fun main() {
    val c = Cell(Item(5))
    println(c.item.id)
    val d = wrap(Item(6))
    println(d.item.id)
}
