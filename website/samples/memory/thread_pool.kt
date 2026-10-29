@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Item(val id: Int)

fun main() {
    Pool<Item>(2).use { pool ->
        context(pool) {
            val item = Item(1)
            val t = spawn {            // Pool is not ThreadSafe
                println(item.id)
                item.free()
            }
            t.join()
        }
    }
}
