// REJECT M0102: released on only one branch of an if. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_branch

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun pick(flag: Boolean) {
    val b = Box(1)
//          ^ M0102
    if (flag) {
        b.free()
    }
    println("done")
}

fun main() {
    context(Heap()) { pick(true) }
}
