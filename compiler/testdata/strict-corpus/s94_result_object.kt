// ACCEPT: Result / runCatching returning plain values computed from a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun parse(b: Box): Result<Int> = runCatching { if (b.v < 0) error("neg") else b.v }

fun main() {
    val b = Box(3)
    println(parse(b).getOrNull())
    val n = Box(-1)
    println(parse(n).isSuccess)
    n.free()
    b.free()
}
