// REJECT M0112: an owned `var` captured by a closure lives in a counted cell that keeps the object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun apply2(f: () -> Int): Int = f()

fun main() {
    var b = Box(7)
    println(apply2 { b.v })
    b.free()
}
