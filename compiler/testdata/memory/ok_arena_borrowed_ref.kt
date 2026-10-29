// ACCEPT: an arena object may refer to a refcounted String through a @Borrowed (non-owning) field. Spec 2.4, 4.4
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_borrowed_ref

import mini.memory.*

class View(val id: Int, @Borrowed val name: String)

fun main() {
    val name = 7.toString() + "x"
    Arena().use { arena ->
        context(arena) {
            val v = View(3, name)
            println(v.id.toString() + v.name)
        }
    }
}
