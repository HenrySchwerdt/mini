// REJECT M0110: Managed code calls a Strict function with an @Owned parameter. Spec 6.2, 7.10
@file:MemoryMode(Mode.Strict)

package memory.boundary_managed_calls_owned

import mini.memory.*

class Box(val v: Int)

fun consume(b: @Owned Box) {
    b.free()
}

@MemoryMode(Mode.Managed)
class Legacy {
    fun run() {
        consume(Box(1))
//              ^ M0110
    }
}

fun main() {
    Legacy().run()
}
