// ACCEPT: a user AutoCloseable used with use{}
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Conn(val id: Int) : AutoCloseable {
    override fun close() { println("closed " + id) }
}

fun main() {
    Conn(7).use { c ->
        println("using " + c.id)
    }
}
