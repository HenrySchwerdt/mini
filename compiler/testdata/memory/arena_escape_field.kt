// REJECT M0105: stored into a field of a longer-lived object. Spec 2.4, 7.5
@file:MemoryMode(Mode.Strict)

package memory.arena_escape_field

import mini.memory.*

class Box(val v: Int)

class Registry {
    var last: Box? = null
}

fun main() {
    val reg = Registry()
    Arena().use { arena ->
        context(arena) {
            reg.last = Box(7)
//                     ^ M0105
        }
    }
    println(reg.last?.v)
}
