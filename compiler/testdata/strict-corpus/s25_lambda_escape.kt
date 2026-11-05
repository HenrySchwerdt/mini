// REJECT M0112: an owning object is captured by an escaping lambda
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun counter(): () -> Int {
    val b = Box(1)
    return { b.v }
}

fun main() {
    println(counter()())
}
