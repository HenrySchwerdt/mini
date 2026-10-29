// ACCEPT: @AllocatedBy selects the allocator by class. Spec 2.2
@file:MemoryMode(Mode.Strict)

package memory.ok_allocated_by

import mini.memory.*

@AllocatedBy(Heap::class)
class Cell(var n: Int)

@AllocatedBy(Pool::class)
class Item(val id: Int)

fun main() {
    val c = Cell(1)
    c.n += 1
    println(c.n)
    c.free()
    Pool<Item>(2).use { pool ->
        context(pool) {
            val i = Item(9)
            println(i.id)
            i.free()
        }
    }
}
