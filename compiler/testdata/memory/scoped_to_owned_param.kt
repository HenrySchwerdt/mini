// REJECT M0106: handing an arena object to an @Owned parameter would let the callee free it. Spec 5.1, 7.6
@file:MemoryMode(Mode.Strict)

package memory.scoped_to_owned_param

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

fun main() {
    Arena().use { arena ->
        context(arena) {
            val b = Box(1)
            consume(b)
//                  ^ M0106
        }
    }
}
