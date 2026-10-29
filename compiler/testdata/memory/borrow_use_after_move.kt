// REJECT M0104: an alias used after its owner was moved to an @Owned parameter. Spec 5.1, 7.4
@file:MemoryMode(Mode.Strict)

package memory.borrow_use_after_move

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a
    consume(a)
    println(b.v)
//          ^ M0104
}

fun main() {
    context(Heap()) { run() }
}
