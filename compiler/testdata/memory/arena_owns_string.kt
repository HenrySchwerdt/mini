// REJECT M0115: an arena object owns a refcounted String; the arena never releases it. Spec 2.4, 7.14a
@file:MemoryMode(Mode.Strict)

package memory.arena_owns_string

import mini.memory.*

class Label(val id: Int, val text: String)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val s = 5.toString()
            val l = Label(5, s)
//                           ^ M0115
            println(l.id)
        }
    }
}
