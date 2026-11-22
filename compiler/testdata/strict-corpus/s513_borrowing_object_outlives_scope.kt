// REJECT M0101: an alias of the borrowing object outlives the object (its block ends and releases it)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Reader { fun get(): Int }

@AllocatedBy(Heap::class)
class Counter(var n: Int) {
    fun reader(): Reader = object : Reader { override fun get(): Int = n }
}

fun f(c: Counter, flag: Boolean): Int {
    var keep: Reader? = null
    if (flag) {
        val r = c.reader()
        keep = r
    }
    return keep?.get() ?: 0
}

fun main() {
    val c = Counter(2)
    println(f(c, true))
    c.free()
}
