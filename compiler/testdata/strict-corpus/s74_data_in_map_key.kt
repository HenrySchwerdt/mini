// ACCEPT: Managed data used as Map keys while a Strict object is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@MemoryMode(Mode.Managed)
data class Key(val id: Int)

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(1)
    val m = mutableMapOf(Key(1) to "a")
    m[Key(2)] = "b"
    println(m.size)
    println(m[Key(1)])
    b.free()
}
