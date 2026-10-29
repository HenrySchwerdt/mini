// REJECT M0110: a Strict object goes into a Managed collection (collections of owned values are P3). Spec 5.4, 7.10
@file:MemoryMode(Mode.Strict)

package memory.boundary_collection

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun run() {
    val list = ArrayList<Box>()
    val b = Box(1)
    list.add(b)
//           ^ M0110
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
