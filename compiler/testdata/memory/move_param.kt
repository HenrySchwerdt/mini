// REJECT M0104: passing to an @Owned parameter moves. Spec 5.1, 7.4
@file:MemoryMode(Mode.Strict)

package memory.move_param

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    val b = Box(1)
    consume(b)
    println(b.v)
//          ^ M0104
}

fun main() {
    context(Heap()) { run() }
}
