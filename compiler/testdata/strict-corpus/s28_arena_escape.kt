// REJECT M0105: an arena object leaves its arena
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

context(a: Arena)
fun make(): Box = Box(1)

fun main() {
    var out: Box? = null
    Arena().use { a ->
        context(a) { out = make() }
    }
    println(out?.v)
}
