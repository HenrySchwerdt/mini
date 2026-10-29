// REJECT M0206: an object of a non-ThreadSafe allocator (Pool) crosses into a thread. Spec 5.9, 7.17
@file:MemoryMode(Mode.Strict)

package memory.thread_nonsafe

import mini.memory.*

class Item(val id: Int)

fun main() {
    Pool<Item>(2).use { pool ->
        context(pool) {
            val item = Item(1)
            val t = spawn {
//                        ^ M0206
                println(item.id)
                item.free()
            }
            t.join()
        }
    }
}
