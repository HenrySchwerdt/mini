// REJECT M0105: `return` carries an arena object out of the arena scope. Spec 2.4, 7.5
@file:MemoryMode(Mode.Strict)

package memory.arena_escape_return

import mini.memory.*

class Box(val v: Int)

fun build(): Box {
    Arena().use { arena ->
        context(arena) {
            return Box(1)
//                 ^ M0105
        }
    }
}

fun main() {
    println(build().v)
}
