// REJECT M0109: a function returns an anonymous object (of a Strict interface type) that borrows a parameter
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int)

fun readerOf(c: Counter): Reader {
    return object : Reader { override fun get(): Int = c.n }
}

fun main() {
    val c = Counter(3)
    val r = readerOf(c)
    println(r.get())
    r.free()
    c.free()
}
