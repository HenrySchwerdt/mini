// REJECT M0112: launch only schedules the coroutine; it runs after the owner it captured was freed (a use after free at run time)
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val b = Box(7)
        launch { println(b.v) }
        b.free()
    }
}
