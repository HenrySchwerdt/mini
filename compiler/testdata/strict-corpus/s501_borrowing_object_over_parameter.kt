// ACCEPT: a function returns an anonymous object and an instance of a local class that borrow a parameter
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

fun readerOf(c: Counter): Reader {
    return object : Reader { override fun get(): Int = c.n * 10 }
}

fun localReader(c: Counter): Reader {
    class R : Reader { override fun get(): Int = c.n + 1 }
    return R()
}

fun main() {
    val c = Counter(4)
    val a = readerOf(c)
    val b = localReader(c)
    println(a.get() + b.get())
    c.n = 1
    println(a.get() + b.get())
    c.free()
}
