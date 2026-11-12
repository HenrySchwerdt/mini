// A task scheduler on a generic binary heap: priorities with ties broken by insertion order, dependencies resolved with a
// topological order, deadlines, simulated time slices, and an LRU cache built on a linked map.

class Heap<T>(private val cmp: Comparator<in T>) {
    private val a = ArrayList<T>()
    val size get() = a.size
    fun isEmpty() = a.isEmpty()
    fun peek(): T? = a.firstOrNull()

    fun push(x: T) {
        a.add(x)
        var i = a.size - 1
        while (i > 0) {
            val p = (i - 1) / 2
            if (cmp.compare(a[i], a[p]) >= 0) break
            a[i] = a[p].also { a[p] = a[i] }
            i = p
        }
    }

    fun pop(): T {
        val top = a[0]
        val last = a.removeAt(a.size - 1)
        if (a.isNotEmpty()) {
            a[0] = last
            var i = 0
            while (true) {
                var m = i
                val l = 2 * i + 1
                val r = 2 * i + 2
                if (l < a.size && cmp.compare(a[l], a[m]) < 0) m = l
                if (r < a.size && cmp.compare(a[r], a[m]) < 0) m = r
                if (m == i) break
                a[i] = a[m].also { a[m] = a[i] }
                i = m
            }
        }
        return top
    }
}

enum class Priority { LOW, NORMAL, HIGH, CRITICAL }

data class Task(val id: Int, val name: String, val priority: Priority, val cost: Int, val deps: List<Int> = emptyList(), val deadline: Int? = null)

class Scheduler(private val tasks: Map<Int, Task>) {
    private var counter = 0

    fun order(): List<Task> {
        val indeg = HashMap<Int, Int>()
        val users = HashMap<Int, MutableList<Int>>()
        for (t in tasks.values) {
            indeg[t.id] = t.deps.size
            for (d in t.deps) users.getOrPut(d) { ArrayList() }.add(t.id)
        }
        val seq = HashMap<Int, Int>()
        val ready = Heap<Task>(compareByDescending<Task> { it.priority }.thenBy { seq[it.id]!! })
        fun enqueue(t: Task) {
            seq[t.id] = counter++
            ready.push(t)
        }
        tasks.values.sortedBy { it.id }.filter { indeg[it.id] == 0 }.forEach { enqueue(it) }
        val out = ArrayList<Task>()
        while (!ready.isEmpty()) {
            val t = ready.pop()
            out.add(t)
            for (u in users[t.id] ?: emptyList<Int>()) {
                indeg[u] = indeg[u]!! - 1
                if (indeg[u] == 0) enqueue(tasks.getValue(u))
            }
        }
        if (out.size != tasks.size) throw IllegalStateException("cycle among " + tasks.keys.filter { k -> out.none { it.id == k } })
        return out
    }
}

class LruCache<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>()
    var hits = 0
    var misses = 0

    operator fun get(k: K): V? {
        val v = map.remove(k)
        if (v == null) {
            misses++
            return null
        }
        hits++
        map[k] = v
        return v
    }

    operator fun set(k: K, v: V) {
        map.remove(k)
        map[k] = v
        if (map.size > capacity) map.remove(map.keys.first())
    }

    override fun toString() = map.entries.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }
}

fun main() {
    val h = Heap<Int>(naturalOrder())
    listOf(5, 3, 8, 1, 9, 2, 7).forEach { h.push(it) }
    print("heap sorted:")
    while (!h.isEmpty()) print(" ${h.pop()}")
    println()
    val tasks = listOf(
        Task(1, "compile", Priority.NORMAL, 30),
        Task(2, "test", Priority.HIGH, 50, listOf(1)),
        Task(3, "lint", Priority.LOW, 10),
        Task(4, "package", Priority.NORMAL, 20, listOf(1, 2), deadline = 120),
        Task(5, "docs", Priority.LOW, 15, listOf(1)),
        Task(6, "hotfix", Priority.CRITICAL, 5),
        Task(7, "deploy", Priority.HIGH, 25, listOf(4, 6), deadline = 100),
    ).associateBy { it.id }
    val order = Scheduler(tasks).order()
    println("order: " + order.joinToString(" -> ") { it.name })
    var clock = 0
    for (t in order) {
        clock += t.cost
        val status = when {
            t.deadline == null -> ""
            clock <= t.deadline -> " (met deadline ${t.deadline})"
            else -> " (MISSED deadline ${t.deadline})"
        }
        println("t=${clock.toString().padStart(3)} ${t.name.padEnd(8)} ${t.priority}$status")
    }
    try {
        Scheduler(mapOf(1 to Task(1, "a", Priority.LOW, 1, listOf(2)), 2 to Task(2, "b", Priority.LOW, 1, listOf(1)), 3 to Task(3, "c", Priority.LOW, 1))).order()
    } catch (e: IllegalStateException) {
        println("error: ${e.message}")
    }
    val cache = LruCache<String, Int>(3)
    for (k in listOf("a", "b", "c", "a", "d", "b", "e", "a", "c")) {
        if (cache[k] == null) cache[k] = k[0].code
        println("access $k -> $cache")
    }
    println("hits=${cache.hits} misses=${cache.misses}")
    val byPriority = tasks.values.groupBy { it.priority }.toSortedMap(reverseOrder())
    println(byPriority.mapValues { e -> e.value.map { it.name } })
}
