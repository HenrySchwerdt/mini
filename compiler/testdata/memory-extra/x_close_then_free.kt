// REJECT M0103: close() ends ownership like free()
@file:MemoryMode(Mode.Strict)

package memory.extra.x_close_then_free

import mini.memory.*

class Res(val n: Int) : AutoCloseable {
    override fun close() { println("close") }
}

context(h: Heap)
fun run() {
    val r = Res(1)
    r.close()
    r.free()
}

fun main() {
    context(Heap()) { run() }
}
