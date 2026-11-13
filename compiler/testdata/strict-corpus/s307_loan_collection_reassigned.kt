// REJECT M0101: assigning a new list to the variable ends the loans of the elements of the old one
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(): Int {
    var xs = mutableListOf<Box>()
    val b = xs.firstOrNull()
    xs = mutableListOf()
    return b?.v ?: 0
}

fun main() {
    println(f())
}
