// REJECT M0105: stored into a global. Spec 2.4, 7.5
@file:MemoryMode(Mode.Strict)

package memory.arena_escape_global

import mini.memory.*

class Box(val v: Int)

var saved: Box? = null

fun main() {
    Arena().use { arena ->
        context(arena) {
            saved = Box(1)
//                  ^ M0105
        }
    }
    println(saved?.v)
}
