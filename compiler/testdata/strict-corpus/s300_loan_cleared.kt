// REJECT M0101: an element read from a list (`xs[0]`) is a loan: `xs.clear()` ends it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun f(xs: MutableList<Box>) {
    val first = xs[0]
    xs.clear()
    println(first.v)
}

fun main() {
    f(mutableListOf())
}
