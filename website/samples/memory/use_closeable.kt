@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Resource(val name: String) : AutoCloseable {
    override fun close() {
        println("close $name")
    }
}

context(h: Heap)
fun run() {
    Resource("a").use { println("using ${it.name}") }   // close() runs on every path out of the block
    val b = Resource("b")
    println("open ${b.name}")
    b.free()                                            // free() calls close() first
}

fun main() {
    context(Heap()) { run() }
}
