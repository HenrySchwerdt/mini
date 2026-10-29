// REJECT M0102: an Arena is a resource itself and must be closed. Spec 2.4, 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_arena_not_closed

import mini.memory.*

class Box(val v: Int)

fun main() {
    val arena = Arena()
//              ^ M0102
    context(arena) {
        val b = Box(1)
        println(b.v)
    }
}
