// REJECT M0101: a borrowed result dies with its owner. Spec 4.3, 7.1
@file:MemoryMode(Mode.Strict)

package memory.uaf_borrowed_result

import mini.memory.*

class Box(val v: Int)

class Holder(var item: Box?)

fun first(h: Holder): @Borrowed Box? = h.item

context(h: Heap)
fun run() {
    val holder = Holder(Box(1))
    @Borrowed val peek = first(holder)
    holder.free()
    println(peek?.v)
//          ^ M0101
}

fun main() {
    context(Heap()) { run() }
}
