// REJECT M0101: the loan of an element outlives the list it was read from (the list ends with its block)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(c: Boolean): Int {
    var kept: Box? = null
    if (c) {
        val xs = mutableListOf<Box>()
        kept = xs.firstOrNull()
    }
    return kept?.v ?: 0
}

fun main() {
    println(f(false))
}
