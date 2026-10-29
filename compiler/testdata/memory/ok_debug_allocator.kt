// ACCEPT: DebugAllocator is a Deallocating allocator that reports leaks at run time. Spec 2.5
@file:MemoryMode(Mode.Strict)

package memory.ok_debug_allocator

import mini.memory.*

class Box(val v: Int)

fun main() {
    val dbg = DebugAllocator()
    context(dbg) {
        val b = Box(1)
        println(b.v)
        dbg.free(b)
    }
    println("done")
}
