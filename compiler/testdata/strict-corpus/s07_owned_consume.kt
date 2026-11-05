// ACCEPT: @Owned transfers ownership; the callee frees
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun consume(b: @Owned Box) {
    println(b.v)
    b.free()
}

fun main() {
    val b = Box(5)
    consume(b)
}
