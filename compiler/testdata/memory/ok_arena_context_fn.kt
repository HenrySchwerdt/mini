// ACCEPT: a context(Arena) function may return arena objects to its caller. Spec 2.4
@file:MemoryMode(Mode.Strict)

package memory.ok_arena_context_fn

import mini.memory.*

class Pair2(val a: Int, val b: Int)

context(arena: Arena)
fun swap(p: Pair2): Pair2 = Pair2(p.b, p.a)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val p = swap(Pair2(1, 2))
            println("${p.a} ${p.b}")
        }
    }
}
