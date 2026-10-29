// REJECT M0109: a field read without move() is only a borrow
@file:MemoryMode(Mode.Strict)

package memory.extra.x_field_to_owned_param

import mini.memory.*

class Box(val v: Int)
class Holder(var item: Box?)

fun consume(b: @Owned Box?) {
    b.free()
}

fun take(h: Holder) {
    consume(h.item)
}

fun main() {
    println(1)
}
