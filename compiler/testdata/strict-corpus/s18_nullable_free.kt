// ACCEPT: a conditionally allocated object is freed on every path
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    var b: Box? = null
    if (1 + 1 == 2) b = Box(2)
    println(b?.v)
    b?.free()
}
