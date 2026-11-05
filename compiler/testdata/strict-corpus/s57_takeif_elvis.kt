// ACCEPT: takeIf / ?: / safe calls on borrowed values
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(3)
    val big = b.takeIf { it.v > 2 }
    println(big?.v ?: 0)
    println(b.takeIf { it.v > 5 }?.v ?: -1)
    b.free()
}
