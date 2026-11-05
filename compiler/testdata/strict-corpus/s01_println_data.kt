// ACCEPT: a data class value printed, in a template, via toString/equals/hashCode; the owner frees it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int, val y: Int)

fun main() {
    val p = P(1, 2)
    println(p)
    println("p = $p")
    println(p.toString())
    val q = P(1, 2)
    println(p == q)
    println(p.hashCode() == q.hashCode())
    q.free()
    p.free()
}
