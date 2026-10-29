// REJECT M0102: arena closed on one path only
@file:MemoryMode(Mode.Strict)

package memory.extra.x_arena_not_closed_branch

import mini.memory.*

class Box(val v: Int)

fun run(flag: Boolean) {
    val arena = Arena()
    context(arena) {
        val b = Box(1)
        println(b.v)
    }
    if (flag) arena.close()
}

fun main() {
    run(true)
}
