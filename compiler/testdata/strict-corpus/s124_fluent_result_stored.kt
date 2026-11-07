// REJECT M0109: a loan of the receiver cannot be stored in an owning field
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

@AllocatedBy(Heap::class)
class Holder(var b: Builder?)

fun main() {
    val b = Builder()
    val h = Holder(null)
    h.b = b.add(1)
    h.free()
    b.free()
}
