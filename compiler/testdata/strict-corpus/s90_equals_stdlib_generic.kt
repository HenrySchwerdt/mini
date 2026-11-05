// ACCEPT: stdlib generic helpers that only read their arguments (maxOf by comparator, to, Pair destructuring of values)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val a = Box(1)
    val b = Box(2)
    val m = maxOf(a.v, b.v)
    val (x, y) = a.v to b.v
    println(m + x + y)
    println(a === a)
    println(a == a)
    println(a != b)
    a.free()
    b.free()
}
