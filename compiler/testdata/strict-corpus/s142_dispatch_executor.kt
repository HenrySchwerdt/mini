// REJECT M0112: a dispatcher queues the block and runs it later
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    Dispatchers.Default.dispatch { println(b.v) }
    b.free()
}
