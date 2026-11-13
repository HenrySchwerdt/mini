// REJECT M0107: a function returns an anonymous object that borrows a parameter: the object is released with its owner, the caller cannot free it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

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
