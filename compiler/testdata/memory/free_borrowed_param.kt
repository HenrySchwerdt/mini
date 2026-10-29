// REJECT M0107: a borrowed parameter cannot be released. Spec 4.2, 7.7
@file:MemoryMode(Mode.Strict)

package memory.free_borrowed_param

import mini.memory.*

class Box(val v: Int)

fun drop(b: Box) {
    b.free()
//  ^ M0107
}

fun main() {
    context(Heap()) {
        val b = Box(1)
        drop(b)
        b.free()
    }
}
