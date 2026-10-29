@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

class Slot(var item: Box?)

// Taking an owned value out of a field is a partial move; the field must be restored.
fun swapOut(s: Slot): @Owned Box? {
    val old = s.item.move()
    s.item = null
    return old
}

fun main() {
    context(Heap()) {
        val slot = Slot(Box(4))
        val x = swapOut(slot)
        println(x?.v)
        x.free()
        slot.free()
    }
}
