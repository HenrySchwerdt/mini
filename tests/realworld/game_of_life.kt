// Cellular automata and simulations: Conway's Game of Life on a torus with pattern detection, a 1-D rule-110 / rule-30 automaton,
// Langton's ant, and a deterministic pseudo random number generator driving a random walk and a Monte Carlo estimate.

class Life(val width: Int, val height: Int) {
    private var cells = Array(height) { BooleanArray(width) }
    var generation = 0
        private set

    fun set(x: Int, y: Int) {
        cells[(y + height) % height][(x + width) % width] = true
    }

    fun load(pattern: List<String>, ox: Int, oy: Int) {
        for ((y, row) in pattern.withIndex()) for ((x, c) in row.withIndex()) if (c == '#') set(ox + x, oy + y)
    }

    private fun neighbors(x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            if (cells[(y + dy + height) % height][(x + dx + width) % width]) n++
        }
        return n
    }

    fun step() {
        val next = Array(height) { BooleanArray(width) }
        for (y in 0 until height) for (x in 0 until width) {
            val n = neighbors(x, y)
            next[y][x] = if (cells[y][x]) n == 2 || n == 3 else n == 3
        }
        cells = next
        generation++
    }

    fun alive() = cells.sumOf { row -> row.count { it } }
    fun snapshot() = cells.joinToString("\n") { row -> row.joinToString("") { if (it) "#" else "." } }
    fun key() = cells.joinToString("") { row -> row.joinToString("") { if (it) "1" else "0" } }
}

fun automaton(rule: Int, width: Int, steps: Int): List<String> {
    var row = BooleanArray(width).also { it[width / 2] = true }
    val out = ArrayList<String>()
    repeat(steps) {
        out.add(row.joinToString("") { if (it) "#" else "." })
        row = BooleanArray(width) { i ->
            val l = row[(i - 1 + width) % width]
            val c = row[i]
            val r = row[(i + 1) % width]
            val idx = (if (l) 4 else 0) or (if (c) 2 else 0) or (if (r) 1 else 0)
            (rule shr idx) and 1 == 1
        }
    }
    return out
}

fun langton(steps: Int, size: Int): Pair<Int, String> {
    val grid = Array(size) { BooleanArray(size) }
    var x = size / 2
    var y = size / 2
    var dir = 0 // 0 up, 1 right, 2 down, 3 left
    val dx = intArrayOf(0, 1, 0, -1)
    val dy = intArrayOf(-1, 0, 1, 0)
    repeat(steps) {
        dir = if (grid[y][x]) (dir + 3) % 4 else (dir + 1) % 4
        grid[y][x] = !grid[y][x]
        x = (x + dx[dir] + size) % size
        y = (y + dy[dir] + size) % size
    }
    val black = grid.sumOf { r -> r.count { it } }
    return black to grid.joinToString("\n") { r -> r.joinToString("") { if (it) "#" else "." } }
}

class Lcg(private var state: Long) {
    fun next(): Int {
        state = (state * 6364136223846793005L + 1442695040888963407L)
        return ((state ushr 33).toInt()) and 0x7FFFFFFF
    }

    fun nextDouble() = next() / 2147483648.0
    fun below(n: Int) = next() % n
}

fun main() {
    val life = Life(10, 8)
    life.load(listOf(".#.", "..#", "###"), 1, 1)
    println("glider generation 0:\n${life.snapshot()}")
    repeat(4) { life.step() }
    println("generation ${life.generation} alive ${life.alive()}:\n${life.snapshot()}")
    repeat(36) { life.step() }
    println("after 40 steps alive=${life.alive()} (glider wraps around the torus)")

    val blinker = Life(5, 5)
    blinker.load(listOf("###"), 1, 2)
    val seen = HashMap<String, Int>()
    while (blinker.key() !in seen) {
        seen[blinker.key()] = blinker.generation
        blinker.step()
    }
    println("blinker repeats after ${blinker.generation - seen.getValue(blinker.key())} generations")

    val r = Life(12, 12)
    r.load(listOf(".##", "##.", ".#."), 5, 5)
    val history = ArrayList<Int>()
    repeat(25) {
        history.add(r.alive())
        r.step()
    }
    println("r-pentomino population: $history")

    println("-- rule 30")
    automaton(30, 31, 12).forEach { println(it) }
    println("-- rule 110 population: " + automaton(110, 41, 20).map { row -> row.count { it == '#' } })

    val (black, picture) = langton(600, 16)
    println("-- langton's ant after 600 steps: $black black cells")
    println(picture.lines().take(6).joinToString("\n"))

    val rng = Lcg(42)
    println("rng: " + List(5) { rng.below(100) })
    var pos = 0
    var minPos = 0
    var maxPos = 0
    repeat(1000) {
        pos += if (rng.next() % 2 == 0) 1 else -1
        minPos = minOf(minPos, pos)
        maxPos = maxOf(maxPos, pos)
    }
    println("random walk: end=$pos min=$minPos max=$maxPos")
    var inside = 0
    val n = 20000
    repeat(n) {
        val x = rng.nextDouble()
        val y = rng.nextDouble()
        if (x * x + y * y <= 1.0) inside++
    }
    println("pi estimate: ${"%.2f".format(4.0 * inside / n)}")
    val dice = IntArray(7)
    repeat(6000) { dice[1 + rng.below(6)]++ }
    println("dice: " + dice.drop(1) + " total ${dice.sum()}")
}
