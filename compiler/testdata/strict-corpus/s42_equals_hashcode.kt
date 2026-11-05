// ACCEPT: user equals/hashCode/compareTo borrow both operands
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Key(val id: Int) : Comparable<Key> {
    override fun equals(other: Any?): Boolean = other is Key && other.id == id
    override fun hashCode(): Int = id
    override fun compareTo(other: Key): Int = id - other.id
}

fun main() {
    val a = Key(1)
    val b = Key(2)
    println(a == b)
    println(a < b)
    println(a.hashCode())
    a.free()
    b.free()
}
