// REJECT M0112: a function returns a sequence over an owner it created itself: the owner dies before the sequence is consumed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Step(val by: Int)

fun make(): Sequence<Int> {
    val s = Step(10)
    val seq = sequence<Int> { for (i in 1..3) yield(i * s.by) }
    return seq
}

fun main() {
    println(make().toList())
}
