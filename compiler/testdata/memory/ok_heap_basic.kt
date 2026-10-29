// ACCEPT: allocate from the Heap, use, free. Spec 2.1, 3.1
@file:MemoryMode(Mode.Strict)

package memory.ok_heap_basic

import mini.memory.*

class Box(val v: Int)

context(h: Heap)
fun make(v: Int): @Owned Box = Box(v)

fun main() {
    context(Heap()) {
        val b = make(21)
        println(b.v * 2)
        b.free()
    }
}
