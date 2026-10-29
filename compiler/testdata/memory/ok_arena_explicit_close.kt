// ACCEPT: an arena may also be closed by hand, even in `finally`. Spec 2.4
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_explicit_close

import mini.memory.*

class Box(val v: Int)

fun main() {
    val arena = Arena()
    try {
        context(arena) {
            val b = Box(3)
            println(b.v)
        }
    } finally {
        arena.close()
    }
}
