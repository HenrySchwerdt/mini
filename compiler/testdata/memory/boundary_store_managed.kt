// REJECT M0110: a Strict object is stored in a field of Managed code. Spec 6.2, 7.10
@file:MemoryMode(Mode.Strict)

package memory.boundary_store_managed

import mini.memory.*

class Box(val v: Int)

@MemoryMode(Mode.Managed)
class Cache {
    var held: Any? = null
}

context(h: Heap)
fun run() {
    val c = Cache()
    val b = Box(1)
    c.held = b
//           ^ M0110
    b.free()
}

fun main() {
    context(Heap()) { run() }
}
