// ACCEPT: assert / check lazy messages referencing a Strict object
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int)

fun main() {
    val p = P(2)
    check(p.x == 2) { "unexpected $p" }
    require(p.x > 0) { "bad $p" }
    println("ok")
    p.free()
}
