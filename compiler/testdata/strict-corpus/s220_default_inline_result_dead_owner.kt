// REJECT M0101: the result of an inline lambda is a loan of an owner declared inside the lambda, which is released when the lambda ends
@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    val b = run {
        val x = Box(1)
        x
    }
    println(b.v)
}
