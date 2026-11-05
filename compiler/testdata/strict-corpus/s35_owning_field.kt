// ACCEPT: an owning field is freed by the owner's destructor-style free()
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
class Engine(val hp: Int)

@AllocatedBy(Heap::class)
class Car(val engine: Engine)

fun main() {
    val c = Car(Engine(100))
    println(c.engine.hp)
    c.free()
}
