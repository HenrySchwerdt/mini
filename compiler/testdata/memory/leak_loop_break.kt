// REJECT M0102: `break` leaves the loop body before the release. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_loop_break

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun scan(limit: Int) {
    for (i in 0 until 10) {
        val b = Box(i)
//              ^ M0102
        if (i == limit) break
        b.free()
    }
}

fun main() {
    context(Heap()) { scan(3) }
}
