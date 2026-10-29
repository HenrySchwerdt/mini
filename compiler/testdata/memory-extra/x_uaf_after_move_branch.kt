// REJECT M0104: moved on one branch only
@file:MemoryMode(Mode.Strict)

package memory.extra.x_uaf_after_move_branch

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run(flag: Boolean) {
    val b = Box(1)
    if (flag) consume(b)
    println(b.v)
}

fun main() {
    context(Heap()) { run(true) }
}
