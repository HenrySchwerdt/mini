// REJECT M0115: an arena object holds a refcounted Managed object in an owning field. Spec 2.4, 7.14a
@file:MemoryMode(Mode.Strict)

package memory.arena_owns_managed

import mini.memory.*

@MemoryMode(Mode.Managed)
class Cache(val n: Int)

class Wrapper(val cache: Cache)

fun main() {
    Arena().use { arena ->
        context(arena) {
            val c = Cache(1)
            val w = Wrapper(c)
//                          ^ M0115
            println(w.cache.n)
        }
    }
}
