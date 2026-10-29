// REJECT M0102: the owned result of a call is discarded. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_ignored_result

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun make(v: Int): @Owned Box = Box(v)

context(h: Heap)
fun run() {
    make(3)
//  ^ M0102
}

fun main() {
    context(Heap()) { run() }
}
