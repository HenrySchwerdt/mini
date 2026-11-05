// graph: build a random directed graph of heap objects (a Node with its own edge list) and run breadth-first searches over it.
// Allocation heavy (1M objects + 1M lists), pointer chasing. Output: reached count, distance sum and eccentricity per source.
const val NODES = 1000000
const val DEGREE = 4
const val SOURCES = 6

class Node(val id: Int) {
    var dist = -1
    val out = ArrayList<Node>()
}

class Lcg(var s: Long) {
    fun next(n: Int): Int {
        s = (s * 1103515245L + 12345L) and 0x7fffffffL
        return ((s shr 8) % n).toInt()
    }
}

fun main() {
    val rng = Lcg(7L)
    val nodes = ArrayList<Node>()
    for (i in 0 until NODES) nodes.add(Node(i))
    for (i in 0 until NODES) {
        val n = nodes[i]
        for (k in 0 until DEGREE) n.out.add(nodes[rng.next(NODES)])
    }
    for (src in 0 until SOURCES) {
        for (n in nodes) n.dist = -1
        val queue = ArrayList<Node>()
        val start = nodes[src * 1009]
        start.dist = 0
        queue.add(start)
        var head = 0
        var sum = 0L
        var ecc = 0
        while (head < queue.size) {
            val n = queue[head]
            head++
            sum += n.dist
            if (n.dist > ecc) ecc = n.dist
            for (m in n.out) {
                if (m.dist < 0) {
                    m.dist = n.dist + 1
                    queue.add(m)
                }
            }
        }
        println("${queue.size} $sum $ecc")
    }
}
