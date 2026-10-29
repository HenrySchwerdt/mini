// REJECT M0101: freed at the end of an iteration, read at the start of the next. Spec 5.7, 7.1
@file:MemoryMode(Mode.Strict)

package memory.uaf_loop

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val b = Box(1)
    for (i in 0 until 2) {
        println(b.v)
//              ^ M0101
        b.free()
    }
}

fun main() {
    context(Heap()) { run() }
}
