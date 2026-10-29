// ACCEPT: an @Owned parameter may be passed on. Spec 5.1
@file:MemoryMode(Mode.Strict)

package memory.ok_owned_forward

import mini.memory.*

class Box(val v: Int)

fun sink(b: @Owned Box) {
    println("sink " + b.v)
    b.free()
}

fun relay(b: @Owned Box) {
    println("relay")
    sink(b)
}

fun main() {
    context(Heap()) { relay(Box(8)) }
}
