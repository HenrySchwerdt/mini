// REJECT M0102: one `when` branch does not release. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_when_branch

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun classify(n: Int) {
    val b = Box(n)
//          ^ M0102
    when {
        n < 0 -> b.free()
        n == 0 -> println("zero")
        else -> b.free()
    }
}

fun main() {
    context(Heap()) { classify(1) }
}
