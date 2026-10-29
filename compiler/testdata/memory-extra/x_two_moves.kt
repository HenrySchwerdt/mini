// REJECT M0104: second move of the same value
@file:MemoryMode(Mode.Strict)

package memory.extra.x_two_moves

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    val b = Box(1)
    consume(b)
    consume(b)
}

fun main() {
    context(Heap()) { run() }
}
