// ACCEPT: copy() and destructuring on a data class (the copy is a new owner)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int, val y: Int)

fun main() {
    val p = P(1, 2)
    val q = p.copy(y = 5)
    val (a, b) = q
    println(a + b)
    println(p)
    q.free()
    p.free()
}
