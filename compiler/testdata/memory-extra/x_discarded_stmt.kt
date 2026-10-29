// REJECT M0102: an allocation used as a statement is never released
@file:MemoryMode(Mode.Strict)

package memory.extra.x_discarded_stmt

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    Box(1)
}

fun main() {
    context(Heap()) { run() }
}
