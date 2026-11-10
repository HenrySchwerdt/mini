// ACCEPT: coroutineScope, supervisorScope, withTimeout and runBlocking itself finish their block before they return: the owner is only borrowed
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    val b = Box(7)
    runBlocking { println(b.v) }
    runBlocking { coroutineScope { println(b.v + 1) } }
    runBlocking { supervisorScope { println(b.v + 2) } }
    runBlocking { println(withTimeout(1000) { b.v + 3 }) }
    b.free()
}
