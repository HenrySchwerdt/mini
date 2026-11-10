// REJECT M0104: an owner moved into a suspend function is used after the call returned
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun consume(@Owned b: Box) {
    delay(1)
    b.free()
}

fun main() {
    runBlocking {
        val b = Box(7)
        consume(b)
        delay(1)
        println(b.v)
    }
}
