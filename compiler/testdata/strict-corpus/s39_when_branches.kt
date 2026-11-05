// ACCEPT: freed on every branch of a when
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(2)
    when (b.v) {
        1 -> { println("one"); b.free() }
        2 -> { println("two"); b.free() }
        else -> b.free()
    }
}
