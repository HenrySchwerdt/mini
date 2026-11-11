// ACCEPT: an owner lives across await, join and calls of suspend functions; a launched child that uses only a copied value runs while it is alive
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun twice(x: Int): Int {
    delay(1)
    return x * 2
}

suspend fun readBox(b: Box): Int {
    delay(1)
    return b.v
}

fun main() {
    runBlocking {
        val b = Box(3)
        val n = b.v
        val d = async { twice(n + 2) }
        val j = launch { println(twice(n)) }
        j.join()
        println(b.v + d.await())
        println(readBox(b))
        b.free()
    }
}
