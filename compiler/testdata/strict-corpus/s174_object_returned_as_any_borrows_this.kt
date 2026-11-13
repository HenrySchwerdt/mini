// REJECT M0109: an anonymous object returned as `Any` borrows the receiver; `Any` may hold a Strict value, so the caller would own it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun label(): Any = object { override fun toString(): String = "counter " + n }
}

fun main() {
    val c = Counter(3)
    val l = c.label()
    c.free()
    println(l)
}
