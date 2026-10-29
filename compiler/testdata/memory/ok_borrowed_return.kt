// ACCEPT: a borrowed result is used while its owner is alive. Spec 4.3
@file:MemoryMode(Mode.Strict)

package memory.ok_borrowed_return

import mini.memory.*

class Box(val v: Int)

class Holder(var item: Box?)

fun first(h: Holder): @Borrowed Box? = h.item

fun main() {
    context(Heap()) {
        val holder = Holder(Box(6))
        @Borrowed val peek = first(holder)
        println(peek?.v)
        holder.free()
    }
}
