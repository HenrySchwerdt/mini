// REJECT M0102: Heap allocation is never released (path: end of scope). Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_end_of_scope

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun work() {
    val b = Box(1)
//          ^ M0102
    println(b.v)
}

fun main() {
    context(Heap()) { work() }
}
