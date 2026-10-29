// ACCEPT: finally releases on the normal path and on the throw path. Spec 5.6
@file:MemoryMode(Mode.Strict)

package memory.ok_try_finally

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun risky(n: Int): Int {
    val b = Box(n)
    try {
        if (n < 0) throw IllegalStateException("neg")
        return b.v * 2
    } finally {
        b.free()
    }
}

fun main() {
    context(Heap()) {
        try {
            println(risky(2))
            println(risky(-1))
        } catch (e: IllegalStateException) {
            println("caught " + e.message)
        }
    }
}
