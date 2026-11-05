// ACCEPT: allocate and free inside a loop
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    var total = 0
    for (i in 1..3) {
        val b = Box(i)
        total += b.v
        b.free()
    }
    println(total)
}
