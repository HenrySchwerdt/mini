// REJECT M0106: an arena object cannot be freed individually. Spec 2.4, 7.6
@file:MemoryMode(Mode.Strict)

package memory.free_scoped

import mini.memory.*

class Box(val v: Int)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val b = Box(1)
            b.free()
//          ^ M0106
        }
    }
}
