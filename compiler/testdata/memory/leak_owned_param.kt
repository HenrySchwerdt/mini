// REJECT M0102: an @Owned parameter must be released or moved on. Spec 4.2, 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_owned_param

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
//          ^ M0102
    println(b.v)
}

fun main() {
    context(Heap()) { consume(Box(1)) }
}
