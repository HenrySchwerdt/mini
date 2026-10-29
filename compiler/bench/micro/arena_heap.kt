@file:MemoryMode(Mode.Strict)

// Arena benchmark, Strict + Heap: the same trees (see arena_managed.kt), malloc'ed one by one and freed by one `free()`
// of the root (the free glue walks the owned children). No reference counts on the nodes.
import mini.memory.*

class Node(val id: Int, @Borrowed val label: String, val left: Node?, val right: Node?)

context(h: Heap)
fun build(depth: Int, seed: Int): @Owned Node? {
    if (depth == 0) return null
    return Node(seed, "n", build(depth - 1, seed * 2), build(depth - 1, seed * 2 + 1))
}

fun checksum(n: Node?): Long {
    if (n == null) return 0L
    return n.id.toLong() + n.label.length + checksum(n.left) * 31 + checksum(n.right) * 17
}

fun main() {
    var total = 0L
    context(Heap()) {
        for (round in 0 until 60) {
            val t = build(15, round + 1)
            total += checksum(t)
            t.free()
        }
    }
    println(total)
}
