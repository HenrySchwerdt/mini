// ACCEPT: a closure passed to a non-inline function that does not keep it borrows the owned object it captures
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun apply2(f: (Int) -> Int): Int = f(1) + f(2)

fun main() {
    val b = Box(10)
    println(apply2 { it + b.v })
    b.free()
}
