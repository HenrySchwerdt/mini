// REJECT M0112: a closure borrowing an owned object is passed to a function that stores it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

class Holder { var f: (() -> Int)? = null }

fun keep(h: Holder, f: () -> Int) { h.f = f }

fun main() {
    val b = Box(1)
    val h = Holder()
    keep(h) { b.v }
    println(h.f?.invoke())
    b.free()
}
