// REJECT M0112: an owned object captured by a lambda that is stored in a field
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

class Handler(var f: (() -> Int)?)

fun main() {
    val b = Box(1)
    val h = Handler({ b.v })
    println(h.f?.invoke())
    b.free()
}
