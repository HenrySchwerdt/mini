// REJECT M0109: GlobalScope.launch is not a child of the runBlocking scope: nothing joins it, the owner outside can be freed first
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    runBlocking {
        GlobalScope.launch { println(b.v) }
    }
    b.free()
}
