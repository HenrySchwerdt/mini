// ACCEPT: a function-typed parameter applied to a borrowed Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun measure(b: Box, f: (Box) -> Int): Int = f(b)

fun main() {
    val b = Box(6)
    println(measure(b) { it.v * 2 })
    b.free()
}
