// REJECT M0102: released only when no exception happens
@file:MemoryMode(Mode.Strict)

package memory.extra.x_try_catch_leak

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run(n: Int) {
    val b = Box(n)
    try {
        if (n < 0) throw IllegalStateException("neg")
        b.free()
    } catch (e: IllegalStateException) {
        println("caught")
    }
}

fun main() {
    context(Heap()) { run(1) }
}
