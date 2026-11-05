// REJECT M0112: a closure held in a local is later stored into a field: it would outlive the owner it borrows
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

class Handler(var f: (() -> Int)?)

fun main() {
    val b = Box(1)
    val g = { b.v }
    println(g())
    val h = Handler(g)
    println(h.f?.invoke())
    b.free()
}
