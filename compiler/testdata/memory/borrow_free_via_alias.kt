// REJECT M0107: an alias cannot release the value; the owner stays responsible. Spec 5.1, 7.7
@file:MemoryMode(Mode.Strict)

package memory.borrow_free_via_alias

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val a = Box(1)
    val b = a
    b.free()
//  ^ M0107
    a.free()
}

fun main() {
    context(Heap()) { run() }
}
