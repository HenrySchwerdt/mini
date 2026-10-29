@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    var total = 0
    Arena().use { arena ->
        context(arena) {
            for (i in 0 until 3) {
                val b = Box(i)     // a new arena object per iteration: nothing is freed until the end
                total += b.v
            }
        }
    }
    println(total)
}
