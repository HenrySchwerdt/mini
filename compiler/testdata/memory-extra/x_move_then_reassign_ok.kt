// ACCEPT: moved variable is re-initialised
@file:MemoryMode(Mode.Strict)

package memory.extra.x_move_then_reassign_ok

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    var b = Box(1)
    consume(b)
    b = Box(2)
    println(b.v)
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
