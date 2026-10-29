@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Pool::class)      // instances of Item must come from a Pool in scope
class Item(val id: Int)

fun main() {
    Pool<Item>(2).use { pool ->
        context(pool) {
            val a = Item(1)
            val b = Item(2)
            println(a.id + b.id)
            a.free()           // pool objects are freed one by one
            b.free()
        }
    }
}
