// REJECT M0102: the engine is not owned by anyone after the car is dropped
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Engine(val hp: Int)

fun main() {
    val e = Engine(1)
    val f = Engine(2)
    println(e.hp)
    e.free()
}
