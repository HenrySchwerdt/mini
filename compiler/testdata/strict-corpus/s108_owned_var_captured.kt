// ACCEPT: a closure that only reads a captured `var` owner borrows it like a `val` (the heap cell of the variable does not count the owner)
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
