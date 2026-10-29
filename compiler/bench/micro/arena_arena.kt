@file:MemoryMode(Mode.Strict)

// Arena benchmark, Strict + Arena: the same trees (see arena_managed.kt), allocated from one Arena per round. No
// reference counts on the nodes, no per-node free: the arena is closed after each round.
import mini.memory.*

class Node(val id: Int, @Borrowed val label: String, val left: Node?, val right: Node?)

context(a: Arena)
fun build(depth: Int, seed: Int): Node? {
    if (depth == 0) return null
    return Node(seed, "n", build(depth - 1, seed * 2), build(depth - 1, seed * 2 + 1))
}

fun checksum(n: Node?): Long {
    if (n == null) return 0L
    return n.id.toLong() + n.label.length + checksum(n.left) * 31 + checksum(n.right) * 17
}

fun main() {
    var total = 0L
    for (round in 0 until 60) {
        Arena().use { arena ->
            context(arena) {
                val t = build(15, round + 1)
                total += checksum(t)
            }
        }
    }
    println(total)
}
