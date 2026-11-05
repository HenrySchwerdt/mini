// ACCEPT: a Strict object's field used with stdlib calls (maxOf, coerce, repeat strings)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(5)
    println(maxOf(b.v, 7))
    println(b.v.coerceAtMost(3))
    println("ab".repeat(b.v - 3))
    b.free()
}
