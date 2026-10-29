@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun build(): Box {
    Arena().use { arena ->
        context(arena) {
            return Box(1)      // the Box lives in `arena`, which is gone after the return
        }
    }
}

fun main() {
    println(build().v)
}
