// REJECT M0112: a GlobalScope coroutine lives as long as the process, so an owner captured by it is a dangling loan
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    GlobalScope.launch { println(b.v) }
    b.free()
}
