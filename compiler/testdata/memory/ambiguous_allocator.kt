// REJECT M0114: two allocators of the same rank in scope. Spec 2.2, 7.14
@file:MemoryMode(Mode.Strict)

package memory.ambiguous_allocator

import mini.memory.*

class Box(val v: Int)

context(a: Arena, h: Heap)
fun both() {
    val b = Box(1)
//          ^ M0114
    println(b.v)
}

fun main() {
    Arena().use { arena -> context(arena, Heap()) { both() } }
}
