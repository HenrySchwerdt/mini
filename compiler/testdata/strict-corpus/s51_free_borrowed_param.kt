// REJECT M0107: freeing a borrowed parameter
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun drop(b: Box) {
    b.free()
}

fun main() {
    val b = Box(1)
    drop(b)
    b.free()
}
