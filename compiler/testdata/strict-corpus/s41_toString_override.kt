// ACCEPT: a user toString that reads the receiver, used by println and templates
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Money(val cents: Int) {
    override fun toString(): String = "$" + (cents / 100) + "." + (cents % 100)
}

fun main() {
    val m = Money(1250)
    println(m)
    println("total: $m")
    m.free()
}
