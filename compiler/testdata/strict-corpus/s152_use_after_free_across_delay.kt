// REJECT M0101: an owner freed before a suspension point is used after it
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val b = Box(7)
        b.free()
        delay(1)
        println(b.v)
    }
}
