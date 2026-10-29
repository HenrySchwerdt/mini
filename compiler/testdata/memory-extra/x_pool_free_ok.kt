// ACCEPT: objects of a pool freed individually, pool closed by use
@file:MemoryMode(Mode.Strict)

package memory.extra.x_pool_free_ok

import mini.memory.*

class Item(val id: Int)

fun main() {
    Pool<Item>(4).use { pool ->
        context(pool) {
            val a = Item(1)
            val b = Item(2)
            pool.free(a)
            b.free()
        }
    }
}
