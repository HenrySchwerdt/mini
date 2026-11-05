// ACCEPT: a user container owning its element
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Item(val v: Int)

@AllocatedBy(Heap::class)
class Cell<T>(val item: T)

fun main() {
    val c = Cell(Item(5))
    println(c.item.v)
    c.free()
}
