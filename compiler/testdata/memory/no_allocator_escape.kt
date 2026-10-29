// REJECT M0204: Strict, escaping allocation, no allocator selectable. Spec 2.2, 7.15
@file:MemoryMode(Mode.Strict)

package memory.no_allocator_escape

import mini.memory.*

class Box(val v: Int)

fun make(): @Owned Box = Box(1)
//                       ^ M0204

fun main() {
    println(make().v)
}
