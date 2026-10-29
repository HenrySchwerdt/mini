// REJECT M0102: assigning to an owned field loses the old value (span: the assignment). Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_overwrite_field

import mini.memory.*

class Box(val v: Int)

class Holder(var item: Box?)

context(h: Heap)
fun replace(holder: Holder) {
    holder.item = Box(2)
//  ^ M0102
}

fun main() {
    context(Heap()) {
        val holder = Holder(Box(1))
        replace(holder)
        holder.free()
    }
}
