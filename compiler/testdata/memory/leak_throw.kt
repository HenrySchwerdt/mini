// REJECT M0102: the explicit `throw` path leaves without releasing. Spec 5.6, 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_throw

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun risky(n: Int) {
    val b = Box(n)
//          ^ M0102
    if (n < 0) throw IllegalArgumentException("negative")
    b.free()
}

fun main() {
    context(Heap()) { risky(1) }
}
