// ACCEPT: an owner that lives across a suspension point (delay) of a runBlocking block and a suspend function is freed in the same coroutine
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

class Plain(var v: Int)

suspend fun inSuspend(): Int {
    val b = Box(10)
    val p = Plain(1)
    delay(1)
    b.v += p.v
    delay(1)
    val r = b.v
    b.free()
    return r
}

fun main() {
    runBlocking {
        val b = Box(7)
        val p = Plain(3)
        delay(1)
        println(b.v + p.v)
        b.free()
        println(inSuspend())
    }
}
