// ACCEPT: runCatching around work on a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun main() {
    val b = Box(0)
    val r = runCatching { 10 / b.v }
    println(r.isFailure)
    b.free()
}
