// A directed graph with arbitrary (also cyclic) edges, built and dropped repeatedly.
class Vertex(val id: Int) {
    val out = ArrayList<Vertex>()
    val inn = ArrayList<Vertex>()
    var mark = 0
}

class Graph(val n: Int) {
    val vs = ArrayList<Vertex>()

    init {
        for (i in 0 until n) vs.add(Vertex(i))
    }

    fun connect(a: Int, b: Int) {
        vs[a].out.add(vs[b])
        vs[b].inn.add(vs[a])
    }

    fun reach(from: Int, stamp: Int): Int {
        var count = 0
        val stack = ArrayList<Vertex>()
        stack.add(vs[from])
        while (stack.size > 0) {
            val v = stack.removeAt(stack.size - 1)
            if (v.mark == stamp) continue
            v.mark = stamp
            count++
            for (w in v.out) stack.add(w)
        }
        return count
    }
}

fun main() {
    var seed = 12345
    fun next(): Int {
        seed = seed * 1103515245 + 12345
        return (seed ushr 8) and 0xFFFF
    }
    var total = 0L
    for (round in 0 until 400) {
        val g = Graph(60)
        for (e in 0 until 200) g.connect(next() % 60, next() % 60)
        g.connect(5, 5) // a self loop
        total += g.reach(0, round + 1)
    }
    println(total)
    val keep = Graph(1000)
    for (e in 0 until 4000) keep.connect(next() % 1000, next() % 1000)
    for (i in 0 until 300) {
        val g = Graph(30)
        for (e in 0 until 90) g.connect(next() % 30, next() % 30)
    }
    println(keep.reach(0, 1))
    println(keep.vs[10].out.size + keep.vs[10].inn.size)
    println("done")
}
