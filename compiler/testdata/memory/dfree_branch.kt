// REJECT M0103: possibly freed twice (freed on one branch, then again). Spec 7.3
@file:MemoryMode(Mode.Strict)

package memory.dfree_branch

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun maybeTwice(flag: Boolean) {
    val b = Box(1)
    if (flag) b.free()
    b.free()
//  ^ M0103
}

fun main() {
    context(Heap()) { maybeTwice(true) }
}
