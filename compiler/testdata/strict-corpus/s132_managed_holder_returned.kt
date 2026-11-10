// ACCEPT: a Managed wrapper around a closure is returned and used while the owner the closure borrows is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Step(val by: Int)

@MemoryMode(Mode.Managed)
class Handler(val f: () -> Int) {
    fun call(): Int = f()
}

fun wrap(f: () -> Int): Handler = Handler(f)

fun main() {
    val s = Step(7)
    val h = wrap { s.by * 2 }
    println(h.call())
    s.free()
}
