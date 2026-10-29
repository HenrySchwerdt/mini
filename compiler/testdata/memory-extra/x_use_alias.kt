// REJECT M0107: `use` on an alias would close the owner object
@file:MemoryMode(Mode.Strict)

package memory.extra.x_use_alias

import mini.memory.*

class Res(val name: String) : AutoCloseable {
    override fun close() { println("close") }
}

context(h: Heap)
fun run() {
    val a = Res("a")
    val b = a
    b.use { println(it.name) }
    a.free()
}

fun main() {
    context(Heap()) { run() }
}
