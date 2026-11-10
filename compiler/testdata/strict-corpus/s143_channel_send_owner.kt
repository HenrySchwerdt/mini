// REJECT M0110: sending an owner through a channel hands it to Managed code that keeps it
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val ch = Channel<Box>(1)
        val b = Box(7)
        ch.send(b)
        println(ch.receive().v)
    }
}
