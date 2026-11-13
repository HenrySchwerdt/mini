// REJECT M0101: an element destructured from an entry of a local map is a loan of the map: `clear()` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    m[1] = Box(1)
    val (k, b) = m.entries.first()
    m.clear()
    println(k + b.v)
}
