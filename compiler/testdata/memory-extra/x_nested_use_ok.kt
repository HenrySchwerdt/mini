// ACCEPT: two nested arenas, inner objects stay inside
@file:MemoryMode(Mode.Strict)

package memory.extra.x_nested_use_ok

import mini.memory.*

class Box(val v: Int)

fun main() {
    Arena().use { outer ->
        context(outer) {
            val a = Box(1)
            Arena().use { inner ->
                context(inner) {
                    val b = Box(2)
                    println(a.v + b.v)
                }
            }
        }
    }
}
