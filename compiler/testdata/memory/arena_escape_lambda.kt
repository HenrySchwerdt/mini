// REJECT M0105: a lambda that captures an arena object escapes the scope. Spec 5.5, 7.5
@file:MemoryMode(Mode.Strict)

package memory.arena_escape_lambda

import mini.memory.*

class Box(val v: Int)

fun main() {
    var cb: (() -> Int)? = null
    Arena().use { arena ->
        context(arena) {
            val b = Box(5)
            cb = { b.v }
//               ^ M0105
        }
    }
    println(cb?.invoke())
}
