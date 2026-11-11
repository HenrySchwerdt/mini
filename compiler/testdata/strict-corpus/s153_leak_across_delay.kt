// REJECT M0102: an owner that lives across a suspension point and is never freed
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun work() {
    val b = Box(7)
    delay(1)
    println(b.v)
}

fun main() {
    runBlocking { work() }
}
