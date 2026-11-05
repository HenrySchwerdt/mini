// REJECT M0112: a closure that captures a borrowing closure is returned
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun make(): () -> Int {
    val b = Box(7)
    val f = { b.v }
    val g = { f() + 1 }
    return g
}

fun main() {
    println(make()())
}
