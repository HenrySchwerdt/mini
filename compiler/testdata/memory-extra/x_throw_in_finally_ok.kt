// ACCEPT: finally frees on the throw path, caller catches
@file:MemoryMode(Mode.Strict)

package memory.extra.x_throw_in_finally_ok

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun risky(n: Int): Int {
    val b = Box(n)
    try {
        if (n < 0) throw IllegalStateException("neg")
        return b.v
    } finally {
        b.free()
    }
}

fun main() {
    context(Heap()) {
        try {
            println(risky(1))
        } catch (e: IllegalStateException) {
            println("x")
        }
    }
}
