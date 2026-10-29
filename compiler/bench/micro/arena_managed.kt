// Arena benchmark, Managed baseline: complete binary trees with an Int and a String label per node, built, walked and
// dropped many times (reference counting frees every node individually). Same work as arena_arena.kt / arena_heap.kt.
class Node(val id: Int, val label: String, val left: Node?, val right: Node?)

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
        val t = build(15, round + 1)
        total += checksum(t)
    }
    println(total)
}
