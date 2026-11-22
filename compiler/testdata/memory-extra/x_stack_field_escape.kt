// REJECT M0102: an object of the default allocator is stored into a field of a parameter (old content unknown)
@file:MemoryMode(Mode.Strict)

package memory.extra.x_stack_field_escape

import mini.memory.*

class Box(val v: Int)
class Holder(var item: Box?)

fun fill(h: Holder) {
    h.item = Box(1)
}

fun main() {
    println(1)
}
