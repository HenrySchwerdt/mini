// ACCEPT with warning M0202: allocation in a loop inside one arena grows without bound. Spec 8, 7.18
@file:MemoryMode(Mode.Strict)

package memory.arena_loop_warn

import mini.memory.*

class Box(val v: Int)

fun main() {
    var total = 0
    Arena().use { arena ->
        context(arena) {
            for (i in 0 until 3) {
                val b = Box(i)
//                      ^ M0202
                total += b.v
            }
        }
    }
    println(total)
}
