// REJECT M0101: the wrapper carries the loan of the owner, so calling it after the free reads freed memory
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
    s.free()
    println(h.call())
}
