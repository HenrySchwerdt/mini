// ACCEPT: borrowed parameters forwarded through several calls (inferred summary, no annotation)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun a(b: Box): Int = b.v
fun bb(b: Box): Int = a(b) + 1
fun c(b: Box): Int = bb(b) + 1

fun main() {
    val b = Box(1)
    println(c(b))
    b.free()
}
