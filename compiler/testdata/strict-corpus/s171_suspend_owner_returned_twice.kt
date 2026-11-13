// REJECT M0104: a suspend function returns an owner and then uses it again (it moved out with the return in the first branch's callee)
@file:MemoryMode(Mode.Strict)

import mini.memory.*
import mini.coroutines.*

@AllocatedBy(Heap::class)
class Box(var v: Int)

suspend fun make(): @Owned Box {
    delay(1)
    return Box(1)
}

suspend fun consume(@Owned b: Box) {
    delay(1)
    b.free()
}

suspend fun twice(): @Owned Box {
    val b = make()
    consume(b)
    return b
}

fun main() {
    runBlocking {
        val b = twice()
        b.free()
    }
}
