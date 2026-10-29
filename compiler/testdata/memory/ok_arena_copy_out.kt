// ACCEPT: a primitive result leaves the arena, the objects do not. Spec 2.4, 7.5
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_copy_out

import mini.memory.*

class Point(val x: Int, val y: Int)

context(a: Arena)
fun diag(n: Int): Point = Point(n, n)

fun main() {
    val total = Arena().use { arena ->
        context(arena) {
            var t = 0
            for (i in 1..3) t += diag(i).x + diag(i).y
            t
        }
    }
    println(total)
}
