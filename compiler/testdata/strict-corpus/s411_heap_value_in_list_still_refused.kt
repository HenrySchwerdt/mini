// REJECT M0110: an object of an explicit allocator is not counted: it still cannot be moved into a list
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Item(val id: Int)

fun main() {
    val ys = mutableListOf<Item>()
    val b = Item(5)
    ys.add(b)
    println(ys.size)
    b.free()
}
