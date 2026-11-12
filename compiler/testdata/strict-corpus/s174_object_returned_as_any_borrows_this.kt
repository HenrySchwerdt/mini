// REJECT M0101: an anonymous object returned as `Any` borrows the receiver (strict-5d: a loan of it, no longer M0109); using it after the receiver was freed is a use after free
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
