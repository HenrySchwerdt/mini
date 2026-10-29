// REJECT M0109: an alias cannot be passed to an @Owned parameter
@file:MemoryMode(Mode.Strict)

package memory.extra.x_alias_to_owned_param

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a
    consume(b)
    a.free()
}

fun main() {
    context(Heap()) { run() }
}
