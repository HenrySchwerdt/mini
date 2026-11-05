// ACCEPT: calling an interface method on a borrowed Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

interface Shape { fun area(): Int }

@AllocatedBy(Heap::class)
class Sq(val s: Int) : Shape { override fun area(): Int = s * s }

fun report(s: Shape) {
    println(s.area())
}

fun main() {
    val q = Sq(3)
    report(q)
    q.free()
}
