// ACCEPT: require / check with a borrowed object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun checked(b: Box): Int {
    require(b.v > 0) { "positive: " + b.v }
    check(b.v < 100)
    return b.v
}

fun main() {
    val b = Box(5)
    println(checked(b))
    b.free()
}
