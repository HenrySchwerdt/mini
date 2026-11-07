// ACCEPT: a lazy sequence built by sequence { } borrows the owner its body reads; it is consumed before the owner is freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Step(val by: Int)

fun main() {
    val s = Step(10)
    val seq = sequence<Int> { for (i in 1..3) yield(i * s.by) }
    println(seq.toList())
    println(seq.map { it + s.by }.sum())
    s.free()
}
