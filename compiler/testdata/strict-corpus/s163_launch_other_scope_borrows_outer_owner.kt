// REJECT M0109: a scope object of its own (CoroutineScope(ctx)) does not join its children when runBlocking returns
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    runBlocking {
        val scope = CoroutineScope(EmptyCoroutineContext)
        scope.launch { println(b.v) }
    }
    b.free()
}
