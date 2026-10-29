@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() = println("close $size")
}

fun fill(b: Buffer): Boolean = b.size > 100

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) return null    // leaks `buf`
    return buf
}

fun main() {
    context(Heap()) { load(8).free() }
}
