@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

fun main() {
    context(Heap()) {          // allocations in this block come from the Heap
        val b = Box(41)        // b owns the allocation
        println(b.v + 1)
        b.free()               // ownership ends here; forgetting this is error M0102
    }
}
