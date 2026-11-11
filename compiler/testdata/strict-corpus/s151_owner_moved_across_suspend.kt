// ACCEPT: an owner moves into a suspend function that suspends and frees it; owners in loops and try/finally with suspension points
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun consume(@Owned b: Box) {
    delay(1)
    println(b.v)
    b.free()
}

suspend fun loop(n: Int) {
    for (i in 1..n) {
        val b = Box(i)
        delay(1)
        println(b.v)
        b.free()
    }
}

suspend fun guarded() {
    val b = Box(40)
    try {
        delay(1)
        println(b.v)
    } finally {
        b.free()
    }
}

fun main() {
    runBlocking {
        val b = Box(5)
        delay(1)
        consume(b)
        loop(3)
        guarded()
    }
}
