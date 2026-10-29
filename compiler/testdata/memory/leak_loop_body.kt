// REJECT M0102: an allocation per iteration that is never released. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_loop_body

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun fillUp() {
    var i = 0
    while (i < 3) {
        val b = Box(i)
//              ^ M0102
        println(b.v)
        i++
    }
}

fun main() {
    context(Heap()) { fillUp() }
}
