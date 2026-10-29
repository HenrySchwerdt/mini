// REJECT M0103: the free sits in a loop, the second iteration frees again. Spec 7.3
@file:MemoryMode(Mode.Strict)

package memory.dfree_loop

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun loopFree() {
    val b = Box(1)
    for (i in 0 until 3) {
        b.free()
//      ^ M0103
    }
}

fun main() {
    context(Heap()) { loopFree() }
}
