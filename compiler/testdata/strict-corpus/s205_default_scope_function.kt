// ACCEPT: a default-allocated object produced inside run/let/apply and handed out: the receiver of the result owns it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(var v: Int)

fun main() {
    val a = Box(1).apply { v = 5 }
    println(a.v)
    val n = Box(2).let { it.v + 40 }
    println(n)
    val b = run { Box(7) }
    println(b.v)
}
