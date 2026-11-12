// A Sudoku solver with constraint propagation (bitmask candidates), backtracking with the most-constrained-cell heuristic,
// puzzle validation and counting solutions; also an N-Queens counter using bit tricks.

class Sudoku(puzzle: String) {
    private val cells = IntArray(81)
    var guesses = 0
        private set

    init {
        require(puzzle.length == 81) { "need 81 cells" }
        for ((i, c) in puzzle.withIndex()) cells[i] = if (c in '1'..'9') c - '0' else 0
    }

    private fun candidates(i: Int): Int {
        var used = 0
        val r = i / 9
        val c = i % 9
        for (k in 0 until 9) {
            used = used or (1 shl cells[r * 9 + k]) or (1 shl cells[k * 9 + c])
        }
        val br = r / 3 * 3
        val bc = c / 3 * 3
        for (dr in 0 until 3) for (dc in 0 until 3) used = used or (1 shl cells[(br + dr) * 9 + bc + dc])
        return (0x3FE and used.inv())
    }

    fun valid(): Boolean {
        for (i in 0 until 81) {
            val v = cells[i]
            if (v == 0) continue
            cells[i] = 0
            val ok = candidates(i) and (1 shl v) != 0
            cells[i] = v
            if (!ok) return false
        }
        return true
    }

    fun solve(countAll: Boolean = false): Int {
        var best = -1
        var bestMask = 0
        var bestCount = 10
        for (i in 0 until 81) {
            if (cells[i] != 0) continue
            val m = candidates(i)
            val n = Integer.bitCount(m)
            if (n < bestCount) {
                best = i
                bestMask = m
                bestCount = n
                if (n <= 1) break
            }
        }
        if (best == -1) return 1
        var solutions = 0
        for (d in 1..9) {
            if (bestMask and (1 shl d) == 0) continue
            guesses++
            cells[best] = d
            solutions += solve(countAll)
            if (solutions > 0 && !countAll) return solutions
            cells[best] = 0
        }
        return solutions
    }

    override fun toString(): String = buildString {
        for (r in 0 until 9) {
            if (r % 3 == 0 && r > 0) appendLine("------+-------+------")
            for (c in 0 until 9) {
                if (c % 3 == 0 && c > 0) append("| ")
                append(if (cells[r * 9 + c] == 0) '.' else ('0' + cells[r * 9 + c])).append(' ')
            }
            appendLine()
        }
    }.trimEnd()
}

object Integer {
    fun bitCount(x: Int): Int {
        var v = x
        var n = 0
        while (v != 0) {
            v = v and (v - 1)
            n++
        }
        return n
    }
}

fun queens(n: Int): Int {
    fun place(row: Int, cols: Int, d1: Int, d2: Int): Int {
        if (row == n) return 1
        var count = 0
        var avail = ((1 shl n) - 1) and (cols or d1 or d2).inv()
        while (avail != 0) {
            val bit = avail and -avail
            avail -= bit
            count += place(row + 1, cols or bit, ((d1 or bit) shl 1) and ((1 shl n) - 1), (d2 or bit) shr 1)
        }
        return count
    }
    return place(0, 0, 0, 0)
}

fun main() {
    val easy = "530070000600195000098000060800060003400803001700020006060000280000419005000080079"
    val hard = "000000907000420180000705026100904000050000040000507009920108000034059000507000000"
    val broken = "55" + ".".repeat(79)
    for ((name, p) in listOf("easy" to easy, "hard" to hard)) {
        val s = Sudoku(p)
        println("$name puzzle valid=${s.valid()}")
        val n = s.solve()
        println("solutions found: $n, guesses: ${s.guesses}")
        println(s)
        println("solution valid: ${s.valid()}")
    }
    println("broken valid=${Sudoku(broken).valid()}")
    val empty = Sudoku("." .repeat(81))
    println("empty grid solvable: ${empty.solve() == 1}")
    println(empty.toString().lines().first())
    val two = Sudoku("2" + "." .repeat(80))
    println("first cell fixed to 2, valid=${two.valid()}")
    println("queens: " + (1..9).map { queens(it) })
}
