// REJECT M0101: `m.entries.first().value` is an element of the map, a loan that `clear()` ends
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    m[1] = Box(1)
    val b = m.entries.first().value
    m.clear()
    println(b.v)
}
