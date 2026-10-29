// REJECT M0102: Pool is Deallocating, so its objects need an individual release. Spec 7.2
@file:MemoryMode(Mode.Strict)

package memory.leak_pool

import mini.memory.*

class Item(val id: Int)

fun main() {
    Pool<Item>(4).use { pool ->
        context(pool) {
            val i = Item(1)
//                  ^ M0102
            println(i.id)
        }
    }
}
