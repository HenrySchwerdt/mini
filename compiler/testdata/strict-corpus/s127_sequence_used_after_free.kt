// REJECT M0101: the sequence reads the owner lazily, so it must not be consumed after the owner is freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Step(val by: Int)

fun main() {
    val s = Step(10)
    val seq = sequence<Int> { for (i in 1..3) yield(i * s.by) }
    s.free()
    println(seq.toList())
}
