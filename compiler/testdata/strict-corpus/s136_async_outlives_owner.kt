// REJECT M0112: async starts a coroutine that can outlive the owner its block captures
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*
import kotlin.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

fun main() {
    runBlocking {
        val b = Box(7)
        val d = async { b.v }
        b.free()
        println(d.await())
    }
}
