// ACCEPT: an extension on a nullable receiver
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun Box?.orZero(): Int = this?.v ?: 0

fun main() {
    val a: Box? = Box(5)
    println(a.orZero())
    val n: Box? = null
    println(n.orZero())
    a?.free()
}
