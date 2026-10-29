// REJECT M0103: owned parameter freed twice
@file:MemoryMode(Mode.Strict)

package memory.extra.x_param_free_twice

import mini.memory.*

class Box(val v: Int)

fun eat(b: @Owned Box) {
    b.free()
    b.free()
}

fun main() {
    context(Heap()) { eat(Box(1)) }
}
