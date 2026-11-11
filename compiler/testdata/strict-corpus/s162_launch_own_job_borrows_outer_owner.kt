// REJECT M0109: launch with a context of its own (a new Job) is not joined by the runBlocking scope
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    runBlocking {
        launch(Job()) { println(b.v) }
    }
    b.free()
}
