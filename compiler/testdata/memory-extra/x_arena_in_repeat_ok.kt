// ACCEPT: the arena lives inside the loop, so no growth warning
@file:MemoryMode(Mode.Strict)

package memory.extra.x_arena_in_repeat_ok

import mini.memory.*

class Box(val v: Int)

fun main() {
    repeat(3) { i ->
        Arena().use { arena ->
            context(arena) {
                val b = Box(i)
                println(b.v)
            }
        }
    }
}
