// ACCEPT: the innermost context allocator wins. Spec 2.2
@file:MemoryMode(Mode.Strict)

package memory.ok_nested_contexts

import mini.memory.*

class Box(val v: Int)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val x = Box(1)
            context(Heap()) {
                val y = Box(2)
                println(x.v + y.v)
                y.free()
            }
        }
    }
}
