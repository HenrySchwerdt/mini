@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Box(val v: Int)

@NoAlloc
context(h: Heap)
fun make(): @Owned Box = Box(1)      // the Box escapes, so it is a real allocation

fun main() {
    context(Heap()) { make().free() }
}
