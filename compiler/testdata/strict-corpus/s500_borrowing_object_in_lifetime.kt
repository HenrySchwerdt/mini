// ACCEPT: a method returns an anonymous object that borrows its receiver: the caller owns the (counted) object, uses it while the receiver lives, and the object is released with its owner
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
    println(r.get())
    c.n = 5
    println(r.get())
    println(c.reader().get())
    c.free()
}
