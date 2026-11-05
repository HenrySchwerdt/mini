// ACCEPT: the object is freed on the exception path by finally
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
    } catch (e: IllegalStateException) {
        println("caught " + e.message)
    } finally {
        b.free()
    }
}
