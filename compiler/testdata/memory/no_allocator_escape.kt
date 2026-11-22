// ACCEPT: Strict, escaping allocation, no allocator selectable: the default allocator (spec 2.2, rule 3). The caller owns the object and releases it when its scope ends
@file:MemoryMode(Mode.Strict)

package memory.no_allocator_escape

import mini.memory.*

class Box(val v: Int)

fun make(): @Owned Box = Box(1)

fun main() {
    println(make().v)
}
