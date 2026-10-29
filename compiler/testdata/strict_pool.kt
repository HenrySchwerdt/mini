@file:MemoryMode(Mode.Strict)

package strict.pool

import mini.memory.*

// A Pool (objects freed one by one, blocks reused), a DebugAllocator over the heap, and @AllocatedBy.

class Item(val id: Int, var weight: Int)

@AllocatedBy(Heap::class)
class Cell(var n: Int)

class Slot(var item: Item?)

fun total(slot: Slot): Int {
    val it = slot.item
    return if (it == null) 0 else it.id * it.weight
}

fun main() {
    Pool<Item>(4).use { pool ->
        context(pool) {
            var sum = 0
            for (round in 1..5) {
                val a = Item(round, 2)
                val b = Item(round * 10, 3)
                sum += a.id * a.weight + b.id * b.weight
                a.free()
                b.free()
            }
            println(sum)
            val s = Slot(Item(7, 6))
            println(total(s))
            s.free()
        }
    }

    val dbg = DebugAllocator()
    context(dbg) {
        val x = Item(1, 2)
        val y = Item(3, 4)
        println(x.id + y.weight)
        dbg.free(x)
        dbg.free(y)
    }
    println(dbg.freeCount)

    val c = Cell(5)
    c.n += 37
    println(c.n)
    c.free()
}
