// REJECT M0205: an escaping allocation in a @NoAlloc function. Spec 8, 7.16
@file:MemoryMode(Mode.Strict)

package memory.noalloc_alloc

import mini.memory.*

class Box(val v: Int)

@NoAlloc
context(h: Heap)
fun make(): @Owned Box = Box(1)
//                       ^ M0205

fun main() {
    context(Heap()) { make().free() }
}
