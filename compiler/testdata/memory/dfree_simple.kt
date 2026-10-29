// REJECT M0103: second free of the same object. Spec 7.3
@file:MemoryMode(Mode.Strict)

package memory.dfree_simple

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun twice() {
    val b = Box(1)
    b.free()
    b.free()
//  ^ M0103
}

fun main() {
    context(Heap()) { twice() }
}
