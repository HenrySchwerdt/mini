@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() = println("close $size")
}

fun fill(b: Buffer): Boolean = b.size > 100

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) {
        buf.free()                 // release on this path too
        return null
    }
    return buf                     // ownership moves to the caller
}

fun main() {
    context(Heap()) { load(8).free() }
}
