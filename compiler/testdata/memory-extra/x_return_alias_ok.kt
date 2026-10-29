// ACCEPT: borrowed result used while the owner lives
@file:MemoryMode(Mode.Strict)

package memory.extra.x_return_alias_ok

import mini.memory.*

class Item(val v: Int)
class Holder(var item: Item?)

fun peek(h: Holder): @Borrowed Item? = h.item

context(h: Heap)
fun run() {
    val holder = Holder(Item(3))
    val p = peek(holder)
    println(p?.v)
    holder.free()
}

fun main() {
    context(Heap()) { run() }
}
