// ACCEPT: StringBuilder.append(Any?) of a Strict object borrows it
@file:MemoryMode(Mode.Strict)

import mini.memory.*

@AllocatedBy(Heap::class)
data class P(val x: Int)

fun main() {
    val p = P(1)
    val sb = StringBuilder()
    sb.append("p=").append(p).append(";")
    println(sb.toString())
    p.free()
}
