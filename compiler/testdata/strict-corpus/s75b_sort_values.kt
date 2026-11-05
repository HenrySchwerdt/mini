// ACCEPT: the threshold is read before the sort
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Limit(val v: Int)

fun main() {
    val l = Limit(2)
    val limit = l.v
    val xs = mutableListOf(3, 1, 2)
    xs.sortBy { if (it > limit) 100 - it else it }
    println(xs)
    l.free()
}
