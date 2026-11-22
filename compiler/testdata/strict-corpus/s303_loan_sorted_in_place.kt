// REJECT M0101: sorting a list in place is a mutating call: the loans of its elements end
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    val first = xs[0]
    xs.sortBy { it.v }
    println(first.v)
}

fun main() {
    println(0)
}
