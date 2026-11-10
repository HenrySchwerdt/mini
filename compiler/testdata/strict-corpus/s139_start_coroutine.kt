// REJECT M0112: startCoroutine keeps the block (the continuation may be resumed later)
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    val block: suspend () -> Unit = { println(b.v) }
    block.startCoroutine(Continuation(EmptyCoroutineContext) { })
    b.free()
}
