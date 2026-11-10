// ACCEPT: a builder method that returns its own receiver returns a loan of it: chained calls, the owner frees once
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Builder {
    var total = 0
    fun add(n: Int): Builder {
        total += n
        return this
    }
    fun twice(): Builder = this
}

fun main() {
    val b = Builder()
    b.add(1).add(2).twice().add(3)
    println(b.total)
    b.free()
}
