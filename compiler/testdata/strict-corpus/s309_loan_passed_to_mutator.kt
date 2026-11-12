// REJECT M0101: a function that receives the list may change it: the loan ends at the call
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun shrink(xs: MutableList<Box>) {
    xs.clear()
}

fun f(xs: MutableList<Box>) {
    val first = xs[0]
    shrink(xs)
    println(first.v)
}

fun main() {
    f(mutableListOf())
}
