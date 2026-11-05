// ACCEPT: several Strict objects passed to a vararg-free helper and to print
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun sumOf(a: Box, b: Box): Int = a.v + b.v

fun main() {
    val a = Box(1)
    val b = Box(2)
    print("sum=")
    println(sumOf(a, b))
    println(sumOf(a, a))
    a.free()
    b.free()
}
