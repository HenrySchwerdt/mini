// ACCEPT: a coroutine that captures no owner can be launched; the owner's value is passed by value
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val b = Box(7)
        val n = b.v
        b.free()
        launch { println(n) }
        println(async { n + 1 }.await())
    }
}
