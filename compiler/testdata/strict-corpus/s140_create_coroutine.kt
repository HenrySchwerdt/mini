// REJECT M0112: createCoroutine returns a continuation that holds the block, which can outlive the owner it captured
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    val k = (suspend { println(b.v) }).createCoroutine(Continuation(EmptyCoroutineContext) { })
    b.free()
    k.resume(Unit)
}
