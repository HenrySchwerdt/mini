// REJECT M0101: the owner a suspend function returned is used after the caller freed it
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
        b.free()
        println(b.v)
    }
}
