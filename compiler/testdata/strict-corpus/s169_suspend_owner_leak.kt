// REJECT M0102: the caller never frees the owner a suspend function returned
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun make(): @Owned Box {
    delay(1)
    return Box(1)
}

fun main() {
    runBlocking {
        val b = make()
        println(b.v)
    }
}
