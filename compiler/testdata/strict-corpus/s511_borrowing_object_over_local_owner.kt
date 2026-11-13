// REJECT M0112: an object that borrows an owner local to the function cannot be returned: the owner ends first
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

fun make(): Reader {
    val c = Counter(1)
    val r = object : Reader { override fun get(): Int = c.n }
    return r
}

fun main() {
    println(make().get())
}
