// Graph algorithms on a generic weighted graph: union-find with path compression, Kruskal's MST, Dijkstra with a path, Floyd-Warshall,
// topological sort, strongly connected components (Tarjan), bipartite check and connected components.

class UnionFind(n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = IntArray(n)
    var sets = n
        private set

    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        var c = x
        while (parent[c] != r) {
            val next = parent[c]
            parent[c] = r
            c = next
        }
        return r
    }

    fun union(a: Int, b: Int): Boolean {
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return false
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> {
                parent[rb] = ra
                rank[ra]++
            }
        }
        sets--
        return true
    }
}

data class Edge<N>(val from: N, val to: N, val weight: Int)

class Graph<N : Comparable<N>>(val directed: Boolean = false) {
    val nodes = sortedSetOf<N>()
    val edges = ArrayList<Edge<N>>()
    private val adj = HashMap<N, MutableList<Edge<N>>>()

    fun edge(a: N, b: N, w: Int = 1): Graph<N> {
        nodes.add(a)
        nodes.add(b)
        edges.add(Edge(a, b, w))
        adj.getOrPut(a) { ArrayList() }.add(Edge(a, b, w))
        if (!directed) adj.getOrPut(b) { ArrayList() }.add(Edge(b, a, w))
        return this
    }

    fun out(n: N): List<Edge<N>> = (adj[n] ?: emptyList<Edge<N>>()).sortedBy { it.to }

    fun mst(): Pair<Int, List<Edge<N>>> {
        val index = nodes.withIndex().associate { it.value to it.index }
        val uf = UnionFind(nodes.size)
        val chosen = ArrayList<Edge<N>>()
        for (e in edges.sortedWith(compareBy({ it.weight }, { it.from }, { it.to }))) {
            if (uf.union(index.getValue(e.from), index.getValue(e.to))) chosen.add(e)
        }
        return chosen.sumOf { it.weight } to chosen
    }

    fun shortest(src: N, dst: N): Pair<Int, List<N>>? {
        val dist = HashMap<N, Int>()
        val prev = HashMap<N, N>()
        val todo = nodes.toMutableSet()
        dist[src] = 0
        while (todo.isNotEmpty()) {
            val u = todo.filter { it in dist }.minWithOrNull(compareBy({ dist.getValue(it) }, { it })) ?: break
            todo.remove(u)
            if (u == dst) break
            for (e in out(u)) {
                val nd = dist.getValue(u) + e.weight
                if (nd < (dist[e.to] ?: Int.MAX_VALUE)) {
                    dist[e.to] = nd
                    prev[e.to] = u
                }
            }
        }
        val d = dist[dst] ?: return null
        val path = ArrayList<N>()
        var cur = dst
        path.add(cur)
        while (cur != src) {
            cur = prev.getValue(cur)
            path.add(cur)
        }
        return d to path.reversed()
    }

    fun topological(): List<N>? {
        val indeg = HashMap<N, Int>()
        for (n in nodes) indeg[n] = 0
        for (e in edges) indeg[e.to] = indeg.getValue(e.to) + 1
        val queue = ArrayDeque(nodes.filter { indeg.getValue(it) == 0 })
        val order = ArrayList<N>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            order.add(n)
            for (e in out(n)) {
                indeg[e.to] = indeg.getValue(e.to) - 1
                if (indeg.getValue(e.to) == 0) queue.addLast(e.to)
            }
        }
        return if (order.size == nodes.size) order else null
    }

    fun components(): List<List<N>> {
        val seen = HashSet<N>()
        val result = ArrayList<List<N>>()
        for (n in nodes) {
            if (n in seen) continue
            val comp = ArrayList<N>()
            val stack = ArrayDeque<N>()
            stack.addLast(n)
            seen.add(n)
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                comp.add(c)
                for (e in out(c)) if (seen.add(e.to)) stack.addLast(e.to)
            }
            result.add(comp.sorted())
        }
        return result
    }

    fun scc(): List<List<N>> {
        var counter = 0
        val index = HashMap<N, Int>()
        val low = HashMap<N, Int>()
        val onStack = HashSet<N>()
        val stack = ArrayList<N>()
        val result = ArrayList<List<N>>()
        fun visit(v: N) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.add(v)
            onStack.add(v)
            for (e in out(v)) {
                if (e.to !in index) {
                    visit(e.to)
                    low[v] = minOf(low.getValue(v), low.getValue(e.to))
                } else if (e.to in onStack) low[v] = minOf(low.getValue(v), index.getValue(e.to))
            }
            if (low[v] == index[v]) {
                val comp = ArrayList<N>()
                while (true) {
                    val w = stack.removeAt(stack.size - 1)
                    onStack.remove(w)
                    comp.add(w)
                    if (w == v) break
                }
                result.add(comp.sorted())
            }
        }
        for (n in nodes) if (n !in index) visit(n)
        return result.sortedBy { it.first() }
    }

    fun bipartite(): Boolean {
        val color = HashMap<N, Int>()
        for (start in nodes) {
            if (start in color) continue
            color[start] = 0
            val queue = ArrayDeque<N>()
            queue.addLast(start)
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                for (e in out(n)) {
                    val c = color[e.to]
                    if (c == null) {
                        color[e.to] = 1 - color.getValue(n)
                        queue.addLast(e.to)
                    } else if (c == color.getValue(n)) return false
                }
            }
        }
        return true
    }
}

fun floydWarshall(n: Int, edges: List<Triple<Int, Int, Int>>): Array<IntArray> {
    val inf = 1_000_000
    val d = Array(n) { i -> IntArray(n) { j -> if (i == j) 0 else inf } }
    for ((a, b, w) in edges) d[a][b] = minOf(d[a][b], w)
    for (k in 0 until n) for (i in 0 until n) for (j in 0 until n) if (d[i][k] + d[k][j] < d[i][j]) d[i][j] = d[i][k] + d[k][j]
    return d
}

fun main() {
    val g = Graph<String>()
    listOf(Triple("A", "B", 7), Triple("A", "D", 5), Triple("B", "C", 8), Triple("B", "D", 9), Triple("B", "E", 7), Triple("C", "E", 5),
        Triple("D", "E", 15), Triple("D", "F", 6), Triple("E", "F", 8), Triple("E", "G", 9), Triple("F", "G", 11)).forEach { (a, b, w) -> g.edge(a, b, w) }
    val (total, tree) = g.mst()
    println("mst weight $total: " + tree.joinToString { "${it.from}${it.to}(${it.weight})" })
    println("shortest A->G: " + g.shortest("A", "G"))
    println("shortest A->Z: " + g.shortest("A", "Z"))
    println("components: " + Graph<Int>().edge(1, 2).edge(2, 3).edge(4, 5).edge(6, 6).components())
    println("bipartite: ${Graph<Int>().edge(1, 2).edge(2, 3).edge(3, 4).edge(4, 1).bipartite()} / ${Graph<Int>().edge(1, 2).edge(2, 3).edge(3, 1).bipartite()}")
    val dag = Graph<String>(directed = true)
    listOf("shirt" to "tie", "tie" to "jacket", "trousers" to "shoes", "trousers" to "belt", "belt" to "jacket", "socks" to "shoes", "shirt" to "belt").forEach { (a, b) -> dag.edge(a, b) }
    println("topological: " + dag.topological())
    println("cyclic topological: " + Graph<Int>(true).edge(1, 2).edge(2, 3).edge(3, 1).topological())
    val d = Graph<Int>(directed = true)
    listOf(1 to 2, 2 to 3, 3 to 1, 3 to 4, 4 to 5, 5 to 6, 6 to 4, 7 to 6, 7 to 8, 8 to 7).forEach { (a, b) -> d.edge(a, b) }
    println("scc: " + d.scc())
    val fw = floydWarshall(4, listOf(Triple(0, 1, 5), Triple(1, 2, 3), Triple(2, 3, 1), Triple(0, 3, 10), Triple(3, 0, 2)))
    for (row in fw) println(row.joinToString(" ") { if (it >= 1_000_000) "inf" else it.toString().padStart(3) })
    val uf = UnionFind(10)
    listOf(0 to 1, 2 to 3, 1 to 3, 4 to 5, 0 to 2, 8 to 9).forEach { (a, b) -> print("${uf.union(a, b)} ") }
    println("-> ${uf.sets} sets, 0~3: ${uf.find(0) == uf.find(3)}, 0~4: ${uf.find(0) == uf.find(4)}")
}
