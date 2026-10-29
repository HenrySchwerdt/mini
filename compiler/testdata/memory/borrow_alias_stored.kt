// REJECT M0109: an alias is stored in an owned field. Spec 5.1, 7.9
@file:MemoryMode(Mode.Strict)

package memory.borrow_alias_stored

import mini.memory.*

class Box(val v: Int)

class Slot(var item: Box?)

context(h: Heap)
fun run() {
    val s = Slot(null)
    val a = Box(1)
    val b = a
    s.item = b
//           ^ M0109
    a.free()
    s.free()
}

fun main() {
    context(Heap()) { run() }
}
