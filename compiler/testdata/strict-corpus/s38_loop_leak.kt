// REJECT M0102: one object per iteration is never released
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    var total = 0
    for (i in 1..3) {
        val b = Box(i)
        total += b.v
    }
    println(total)
}
