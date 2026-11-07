// ACCEPT: coroutineScope, supervisorScope and withTimeout finish their block before they return, so the block only borrows the owner
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Cfg(val n: Int)

suspend fun work(c: Cfg): Int {
    delay(1)
    return c.n * 2
}

fun main() {
    val c = Cfg(4)
    runBlocking {
        println(work(c))
        coroutineScope { println(c.n) }
        supervisorScope { println(c.n + 1) }
        println(withTimeout(1000) { c.n + 2 })
        println(withTimeoutOrNull(1000) { c.n + 3 })
    }
    c.free()
}
