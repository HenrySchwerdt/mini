// REJECT M0101+M0107: a method returns an anonymous object that borrows its receiver: the receiver is freed first (M0101), and the caller cannot free the object (M0107)
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
