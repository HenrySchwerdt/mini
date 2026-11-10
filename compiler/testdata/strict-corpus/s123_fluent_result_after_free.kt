// REJECT M0101: the loan returned by `add` dies with its receiver
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Builder {
    var total = 0
    fun add(n: Int): Builder {
        total += n
        return this
    }
}

fun main() {
    val b = Builder()
    val c = b.add(1)
    b.free()
    println(c.total)
}
