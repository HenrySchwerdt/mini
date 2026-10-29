// ACCEPT: use {} and free() on an AutoCloseable both run close(). Spec 3.2
@file:MemoryMode(Mode.Strict)

package memory.ok_use_autocloseable

import mini.memory.*

class Res(val name: String) : AutoCloseable {
    override fun close() {
        println("close " + name)
    }
}

context(h: Heap)
fun run() {
    Res("a").use { println("using " + it.name) }
    val b = Res("b")
    println("open b")
    b.free()
    val c = Res("c")
    c.use { println("using " + it.name) }
}

fun main() {
    context(Heap()) { run() }
}
