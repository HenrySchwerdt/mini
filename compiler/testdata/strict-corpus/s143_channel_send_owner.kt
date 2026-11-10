// REJECT M0110: sending an owner through a channel hands it to Managed code that keeps it
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun give(ch: Channel<Box>, @Owned b: Box) {
    ch.send(b)
}

fun main() {
    runBlocking {
        val ch = Channel<Box>(1)
        give(ch, Box(7))
        println(1)
    }
}
