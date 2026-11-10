// REJECT M0112: an owner declared inside the runBlocking block can be freed before the launched child runs
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val o = Box(1)
        launch { println(o.v) }
        o.free()
    }
}
