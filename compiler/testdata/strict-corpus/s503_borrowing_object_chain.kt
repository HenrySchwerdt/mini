// ACCEPT: a borrowing object over a borrowing object, with a default-allocated receiver
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

fun plusOne(r: Reader): Reader = object : Reader { override fun get(): Int = r.get() + 1 }

fun main() {
    val c = Counter(1)
    val r = c.reader()
    val p = plusOne(r)
    val q = plusOne(p)
    println(q.get())
    c.n = 10
    println(q.get())
}
