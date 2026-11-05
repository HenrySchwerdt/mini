// ACCEPT: the same with an inline callee: the lambda is read like code at the call
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

inline fun apply2(f: (Int) -> Int): Int = f(1) + f(2)

fun main() {
    val b = Box(10)
    println(apply2 { it + b.v })
    b.free()
}
