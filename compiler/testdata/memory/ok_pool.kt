// ACCEPT: objects of a Pool are released one by one. Spec 2.3
@file:MemoryMode(Mode.Strict)

package memory.ok_pool

import mini.memory.*

class Item(val id: Int)

fun main() {
    Pool<Item>(2).use { pool ->
        context(pool) {
            val a = Item(1)
            val b = Item(2)
            println(a.id + b.id)
            a.free()
            b.free()
        }
    }
}
