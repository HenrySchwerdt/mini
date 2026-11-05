// REJECT M0102: the throw edge leaks the object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun risky(b: Box) {
    if (b.v > 0) throw IllegalStateException("boom")
}

fun main() {
    val b = Box(1)
    try {
        risky(b)
        b.free()
    } catch (e: IllegalStateException) {
        println("caught")
    }
}
