// ACCEPT: owner re-created in every iteration
@file:MemoryMode(Mode.Strict)

package memory.extra.x_free_in_loop_ok

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    var b = Box(0)
    for (i in 1..3) {
        b.free()
        b = Box(i)
    }
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
