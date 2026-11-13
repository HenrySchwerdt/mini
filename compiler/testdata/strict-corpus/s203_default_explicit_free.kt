// ACCEPT: free() on an owner of the default allocator releases it at once (and the owner's scope end does not release it again); freeing the owner of a tree frees the tree
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)
class Holder(val item: Item, val name: String)

fun make(n: Int): Holder = Holder(Item(n), "h" + n)

fun main() {
    val a = make(1)
    println(a.name + " " + a.item.id)
    a.free()
    val b = make(2)
    if (b.item.id == 2) {
        b.free()
    } else {
        println("not freed")
    }
    var c = make(3)
    c.free()
    c = make(4)
    println(c.name)
    val d: Holder? = if (c.item.id > 3) make(5) else null
    if (d != null) {
        println(d.name)
        d.free()
    }
}
