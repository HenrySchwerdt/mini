// ACCEPT: passing a Strict object to stdlib functions that take Any (hashCode, toString, string compare via equals)
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int)

fun main() {
    val p = P(1)
    val s: Any = "x"
    println(p.equals(s))
    println(s == p)
    val q = P(1)
    println(p.hashCode() == q.hashCode())
    q.free()
    p.free()
}
