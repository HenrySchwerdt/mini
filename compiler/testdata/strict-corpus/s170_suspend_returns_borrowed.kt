// REJECT M0109: a suspend function returns a borrowed parameter as an owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun same(b: Box): @Owned Box {
    delay(1)
    return b
}

fun main() {
    runBlocking {
        val b = Box(1)
        val c = same(b)
        c.free()
        b.free()
    }
}
