// ACCEPT: an owner declared and freed between two suspension points of a suspend function (it lives in a heap cell of the state machine, which must not release it again)
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

class Plain(var v: Int)

suspend fun heapOwner() {
    delay(1)
    val b = Box(7)
    println(b.v)
    b.free()
}

suspend fun plainValue() {
    delay(1)
    val p = Plain(5)
    println(p.v)
}

fun main() {
    runBlocking {
        heapOwner()
        plainValue()
    }
}
