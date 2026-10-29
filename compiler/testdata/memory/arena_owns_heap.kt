// REJECT M0115: an arena object owns a Heap object (it would leak: the arena never runs dispose). Spec 2.4, 7.14a
@file:MemoryMode(Mode.Strict)

package memory.arena_owns_heap

import mini.memory.*

class Box(val v: Int)

class Holder(var item: Box?)

fun main() {
    val heap = Heap()
    Arena().use { arena ->
        context(arena) {
            val h = Holder(null)
            context(heap) {
                val b = Box(1)
                h.item = b
//                       ^ M0115
            }
        }
    }
}
