// REJECT M0101: an element read through the `values` view of a map is a loan of the map: `clear()` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    m[1] = Box(1)
    val b = m.values.first()
    m.clear()
    println(b.v)
}
