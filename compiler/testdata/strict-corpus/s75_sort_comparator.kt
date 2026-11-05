// REJECT M0112: the comparator closure created by sortBy captures an owned object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Limit(val v: Int)

fun main() {
    val l = Limit(2)
    val xs = mutableListOf(3, 1, 2)
    xs.sortBy { if (it > l.v) 100 - it else it }
    println(xs)
    l.free()
}
