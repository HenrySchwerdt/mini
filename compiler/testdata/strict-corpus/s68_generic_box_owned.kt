// ACCEPT: a generic holder of a Strict object; the holder owns it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Item(val v: Int)

@AllocatedBy(Heap::class)
class Holder<T>(val value: T)

fun <T> peek(h: Holder<T>): T = h.value

fun main() {
    val h = Holder(Item(3))
    val i = peek(h)
    println(i.v)
    h.free()
}
