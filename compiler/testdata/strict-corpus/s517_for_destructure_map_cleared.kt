// REJECT M0101: the value of `for ((k, b) in m)` is a loan of the map: `m.clear()` in the body ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val m = mutableMapOf<Int, Box>()
    m[1] = Box(1)
    for ((k, b) in m) {
        m.clear()
        println(k + b.v)
    }
}
