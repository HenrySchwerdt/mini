// REJECT M0109: a function that launches a coroutine over its borrowed parameter lets the coroutine outlive the caller's owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun CoroutineScope.start(b: Box) {
    launch { println(b.v) }
}

fun main() {
    runBlocking {
        val b = Box(7)
        start(b)
        b.free()
    }
}
