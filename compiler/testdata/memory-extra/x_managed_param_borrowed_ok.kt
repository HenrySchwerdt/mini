// ACCEPT: Strict value to a Managed function with a @Borrowed parameter
@file:MemoryMode(Mode.Strict)

package memory.extra.x_managed_param_borrowed_ok

import mini.memory.*

@MemoryMode(Mode.Managed)
class Logger {
    fun log(b: @Borrowed Box) { println(b.v) }
}

class Box(val v: Int)

fun main() {
    val l = Logger()
    val b = Box(2)
    l.log(b)
}
