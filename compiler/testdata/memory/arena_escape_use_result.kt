// REJECT M0105: the value of the `use` block leaves the arena. Spec 2.4, 7.5
@file:MemoryMode(Mode.Strict)

package memory.arena_escape_use_result

import mini.memory.*

class Box(val v: Int)

fun main() {
    val n: Box = Arena().use { arena -> context(arena) { Box(2) } }
//                                                       ^ M0105
    println(n.v)
}
