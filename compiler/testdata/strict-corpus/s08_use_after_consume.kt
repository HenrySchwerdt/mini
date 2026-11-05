// REJECT M0104: the value was handed to an @Owned parameter
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

fun main() {
    val b = Box(5)
    consume(b)
    println(b.v)
}
