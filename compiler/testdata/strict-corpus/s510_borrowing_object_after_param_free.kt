// REJECT M0101: the object over a parameter is used after the parameter owner was freed
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
    c.free()
    println(r.get())
}
