// REJECT M0102: printing does not release the object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int, val y: Int)

fun main() {
    val p = P(1, 2)
    println(p)
}
