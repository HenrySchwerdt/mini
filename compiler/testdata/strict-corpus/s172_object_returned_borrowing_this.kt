// REJECT M0109: a method returns an anonymous object (of a Strict interface type) that borrows its receiver; the caller would own it and free the receiver first
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

fun main() {
    val c = Counter(3)
    val r = c.reader()
    c.free()
    println(r.get())
    r.free()
}
