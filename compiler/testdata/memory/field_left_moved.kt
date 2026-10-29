// REJECT M0113: an owned field is moved out and not restored before the function ends. Spec 5.3, 7.13
@file:MemoryMode(Mode.Strict)

package memory.field_left_moved

import mini.memory.*

class Box(val v: Int)

class Slot(var item: Box?)

fun steal(s: Slot): @Owned Box? {
    val b = s.item.move()
//          ^ M0113
    return b
}

fun main() {
    context(Heap()) {
        val s = Slot(Box(1))
        val b = steal(s)
        b.free()
        s.free()
    }
}
