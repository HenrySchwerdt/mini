// REJECT M0101: the variable of a for loop is a loan of an element: clearing the list in the body ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    for (b in xs) {
        xs.clear()
        println(b.v)
    }
}

fun main() {
    f(mutableListOf())
}
