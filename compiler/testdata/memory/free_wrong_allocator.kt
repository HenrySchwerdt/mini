// REJECT M0108: `pool.free(x)` for an object that came from the Heap. Spec 3.3, 7.8
@file:MemoryMode(Mode.Strict)

package memory.free_wrong_allocator

import mini.memory.*

class Box(val v: Int)

fun main() {
    Pool<Box>(2).use { pool ->
        val heap = Heap()
        context(heap) {
            val b = Box(1)
            pool.free(b)
//          ^ M0108
        }
    }
}
