// ACCEPT: launch / async on the scope of runBlocking, coroutineScope and withTimeout are joined before the call returns: they may borrow an owner declared outside it
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun readLater(b: Box): Int {
    delay(1)
    return b.v
}

fun main() {
    val b = Box(7)
    runBlocking {
        val j = launch { delay(1); println(b.v) }
        val d = async { readLater(b) + 1 }
        j.join()
        println(d.await())
        coroutineScope {
            launch { println(b.v + 2) }
            launch { launch { println(b.v + 3) } }
        }
        println(withTimeout(1000) { async { b.v + 4 }.await() })
        launch { delay(1); println(b.v + 5) }
        println(0)
    }
    println(nested(b))
    b.free()
}

fun nested(c: Box): Int {
    var r = 0
    runBlocking {
        launch { r = c.v * 2 }
    }
    return r
}
