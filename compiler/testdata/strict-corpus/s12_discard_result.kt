// REJECT M0102: the result of make() is owned by the caller and dropped
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Box(val v: Int)

fun make(v: Int): Box = Box(v)

fun main() {
    make(3)
}
