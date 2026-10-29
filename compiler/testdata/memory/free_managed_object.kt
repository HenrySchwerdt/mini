// REJECT M0107: Managed (refcounted) objects are not owned by Strict code. Spec 6, 7.7
@file:MemoryMode(Mode.Strict)

package memory.free_managed_object

import mini.memory.*

@MemoryMode(Mode.Managed)
class Cache(val n: Int)

fun main() {
    val c = Cache(1)
    c.free()
//  ^ M0107
}
