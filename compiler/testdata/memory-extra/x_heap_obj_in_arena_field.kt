// REJECT M0115: heap object stored through the constructor of an arena object
@file:MemoryMode(Mode.Strict)

package memory.extra.x_heap_obj_in_arena_field

import mini.memory.*

class Box(val v: Int)
class Wrap(val inner: Box)

fun main() {
    val heap = Heap()
    Arena().use { arena ->
        context(heap) {
            val b = Box(1)
            context(arena) {
                val w = Wrap(b)
                println(w.inner.v)
            }
        }
    }
}
