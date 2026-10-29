// ACCEPT: `alloc.free(x)` is the same as `x.free()` and names the allocator. Spec 3.3
@file:MemoryMode(Mode.Strict)

package memory.ok_alloc_free_spelling

import mini.memory.*

class Box(val v: Int)

fun main() {
    val heap = Heap()
    context(heap) {
        val a = Box(1)
        val b = Box(2)
        println(a.v + b.v)
        heap.free(a)
        b.free()
    }
}
