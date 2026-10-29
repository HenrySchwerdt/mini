// ACCEPT: an @Owned parameter returned as owned
@file:MemoryMode(Mode.Strict)

package memory.extra.x_owned_param_return_ok

import mini.memory.*

class Box(val v: Int)

fun pass(b: @Owned Box): @Owned Box = b

context(h: Heap)
fun run() {
    val b = pass(Box(4))
    println(b.v)
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
