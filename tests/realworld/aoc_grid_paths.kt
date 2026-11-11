// Advent-of-Code style puzzle solver: grid parsing, BFS shortest path, Dijkstra with weights, flood fill regions.

data class Point(val x: Int, val y: Int) {
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
    fun manhattan(o: Point) = kotlin.math.abs(x - o.x) + kotlin.math.abs(y - o.y)
}

val DIRS = listOf(Point(1, 0), Point(-1, 0), Point(0, 1), Point(0, -1))

class Grid(lines: List<String>) {
    val height = lines.size
    val width = lines.maxOf { it.length }
    private val cells = lines.map { it.padEnd(width, '#').toCharArray() }

    operator fun get(p: Point): Char = if (p.x in 0 until width && p.y in 0 until height) cells[p.y][p.x] else '#'
    operator fun set(p: Point, c: Char) {
        cells[p.y][p.x] = c
    }

    fun find(c: Char): Point? {
        for (y in 0 until height) for (x in 0 until width) if (cells[y][x] == c) return Point(x, y)
        return null
    }

    fun points(): Sequence<Point> = sequence {
        for (y in 0 until height) for (x in 0 until width) yield(Point(x, y))
    }

    fun neighbors(p: Point): List<Point> = DIRS.map { p + it }.filter { this[it] != '#' }

    override fun toString(): String = cells.joinToString("\n") { String(it) }
}

fun bfs(grid: Grid, start: Point, goal: Point): List<Point>? {
    val prev = HashMap<Point, Point>()
    val queue = ArrayDeque<Point>()
    queue.addLast(start)
    prev[start] = start
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur == goal) {
            val path = ArrayList<Point>()
            var p = goal
            while (p != start) {
                path.add(p)
                p = prev.getValue(p)
            }
            path.add(start)
            path.reverse()
            return path
        }
        for (n in grid.neighbors(cur)) {
            if (n !in prev) {
                prev[n] = cur
                queue.addLast(n)
            }
        }
    }
    return null
}

/** A binary min-heap (the standard library of Kotlin has no priority queue). */
class MinHeap<T>(private val order: Comparator<T>) {
    private val items = ArrayList<T>()

    fun isNotEmpty() = items.isNotEmpty()

    fun add(x: T) {
        items.add(x)
        var i = items.size - 1
        while (i > 0) {
            val parent = (i - 1) / 2
            if (order.compare(items[i], items[parent]) >= 0) break
            val t = items[i]; items[i] = items[parent]; items[parent] = t
            i = parent
        }
    }

    fun pop(): T {
        val top = items[0]
        val last = items.removeAt(items.size - 1)
        if (items.isNotEmpty()) {
            items[0] = last
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var m = i
                if (l < items.size && order.compare(items[l], items[m]) < 0) m = l
                if (r < items.size && order.compare(items[r], items[m]) < 0) m = r
                if (m == i) break
                val t = items[i]; items[i] = items[m]; items[m] = t
                i = m
            }
        }
        return top
    }
}

fun cost(c: Char): Int = if (c.isDigit()) c - '0' else 1

fun dijkstra(grid: Grid, start: Point, goal: Point): Int {
    val dist = HashMap<Point, Int>()
    val open = MinHeap<Pair<Int, Point>>(compareBy({ it.first }, { it.second.x }, { it.second.y }))
    dist[start] = 0
    open.add(0 to start)
    while (open.isNotEmpty()) {
        val (d, p) = open.pop()
        if (p == goal) return d
        if (d > (dist[p] ?: Int.MAX_VALUE)) continue
        for (n in grid.neighbors(p)) {
            val nd = d + cost(grid[n])
            if (nd < (dist[n] ?: Int.MAX_VALUE)) {
                dist[n] = nd
                open.add(nd to n)
            }
        }
    }
    return -1
}

fun regions(grid: Grid): List<Int> {
    val seen = HashSet<Point>()
    val sizes = ArrayList<Int>()
    for (p in grid.points()) {
        if (grid[p] == '#' || p in seen) continue
        var size = 0
        val stack = ArrayDeque<Point>()
        stack.addLast(p)
        seen.add(p)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            size++
            for (n in grid.neighbors(c)) if (seen.add(n)) stack.addLast(n)
        }
        sizes.add(size)
    }
    return sizes.sortedDescending()
}

val MAZE = """
#############
#S..#.....#.#
#.#.#.###.#.#
#.#...#...#.#
#.#####.###.#
#.......#..E#
#############
""".trimIndent()

val WEIGHTED = """
#########
#S19#1111
#1#9#1##1
#1#1#1#E1
#1111#1##
#########
""".trimIndent()

val ISLANDS = """
##########
#..#..#..#
#..#..#..#
####..####
#.#....#.#
#.#.##.#.#
##########
""".trimIndent()

fun main() {
    val maze = Grid(MAZE.lines())
    val s = maze.find('S')!!
    val e = maze.find('E')!!
    println("grid ${maze.width}x${maze.height}, start=$s end=$e, manhattan=${s.manhattan(e)}")
    val path = bfs(maze, s, e)
    if (path == null) println("no path") else {
        println("shortest path length: ${path.size - 1}")
        val marked = Grid(MAZE.lines())
        for (p in path.drop(1).dropLast(1)) marked[p] = '*'
        println(marked)
    }
    val w = Grid(WEIGHTED.lines())
    val ws = w.find('S')!!
    val we = w.find('E')!!
    println("weighted cost: ${dijkstra(w, ws, we)}")
    val blocked = Grid(listOf("S#E"))
    println("blocked: ${bfs(blocked, blocked.find('S')!!, blocked.find('E')!!)}")
    val islands = Grid(ISLANDS.lines())
    println("regions: ${regions(islands)}")
    println("open cells: ${islands.points().count { islands[it] == '.' }}")
}
