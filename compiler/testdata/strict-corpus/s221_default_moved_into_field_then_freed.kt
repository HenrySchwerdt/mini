// REJECT M0101: the owner of an object of the default allocator moved into a field is freed with its parent; the field is used after the parent's free
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)
class Holder(val item: Item)

fun main() {
    val h = Holder(Item(1))
    val i = h.item
    h.free()
    println(i.id)
}
