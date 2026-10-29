// REJECT M0104: moved in the previous iteration of the loop. Spec 5.7, 7.4
@file:MemoryMode(Mode.Strict)

package memory.move_loop

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    val b = Box(1)
    for (i in 0 until 2) {
        consume(b)
//              ^ M0104
    }
}

fun main() {
    context(Heap()) { run() }
}
