// ACCEPT: moving a value out of an owned field and restoring the field at once. Spec 5.3
@file:MemoryMode(Mode.Strict)

package memory.ok_field_move_out

import mini.memory.*

class Box(val v: Int)

class Slot(var item: Box?)

fun swapOut(s: Slot): @Owned Box? {
    val old = s.item
    s.item = null
    return old
}

fun main() {
    context(Heap()) {
        val slot = Slot(Box(4))
        val x = swapOut(slot)
        println(x?.v)
        println(slot.item == null)
        x.free()
        slot.free()
    }
}
