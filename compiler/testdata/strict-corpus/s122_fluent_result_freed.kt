// REJECT M0107: the result of a method that returns its receiver is a loan; freeing it releases a value the caller does not own
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
    c.free()
    b.free()
}
