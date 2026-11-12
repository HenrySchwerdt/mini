// Game search: tic-tac-toe with minimax and alpha-beta pruning, a perfect-play check over the whole game tree (memoized by board key),
// and a small Connect-Three solver using the same generic search interface.

interface Game<S, M> {
    fun moves(s: S): List<M>
    fun play(s: S, m: M): S
    fun score(s: S, maximizing: Boolean): Int?   // null while the game goes on
    fun key(s: S): String
}

class Searcher<S, M>(private val g: Game<S, M>) {
    var nodes = 0
    private val memo = HashMap<String, Int>()

    fun value(s: S, maximizing: Boolean, alpha0: Int = -1000, beta0: Int = 1000): Int {
        nodes++
        g.score(s, maximizing)?.let { return it }
        var alpha = alpha0
        var beta = beta0
        var best = if (maximizing) -1000 else 1000
        for (m in g.moves(s)) {
            val v = value(g.play(s, m), !maximizing, alpha, beta)
            if (maximizing) {
                best = maxOf(best, v)
                alpha = maxOf(alpha, best)
            } else {
                best = minOf(best, v)
                beta = minOf(beta, best)
            }
            if (beta <= alpha) break
        }
        return best
    }

    fun full(s: S, maximizing: Boolean): Int {
        val k = g.key(s) + maximizing
        memo[k]?.let { return it }
        nodes++
        val v = g.score(s, maximizing) ?: g.moves(s).map { full(g.play(s, it), !maximizing) }.let { if (maximizing) it.max() else it.min() }
        memo[k] = v
        return v
    }

    fun best(s: S, maximizing: Boolean): M {
        var bestMove: M? = null
        var bestValue = if (maximizing) -1000 else 1000
        for (m in g.moves(s)) {
            val v = value(g.play(s, m), !maximizing)
            if ((maximizing && v > bestValue) || (!maximizing && v < bestValue)) {
                bestValue = v
                bestMove = m
            }
        }
        return bestMove!!
    }
}

object TicTacToe : Game<String, Int> {
    private val lines = listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8), listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8), listOf(0, 4, 8), listOf(2, 4, 6))
    override fun moves(s: String) = s.indices.filter { s[it] == '.' }
    override fun play(s: String, m: Int): String = s.substring(0, m) + (if (s.count { it != '.' } % 2 == 0) 'X' else 'O') + s.substring(m + 1)
    override fun score(s: String, maximizing: Boolean): Int? {
        for (l in lines) {
            val c = s[l[0]]
            if (c != '.' && c == s[l[1]] && c == s[l[2]]) return if (c == 'X') 10 - s.count { it == '.' } else -10 + s.count { it == '.' }
        }
        return if ('.' !in s) 0 else null
    }

    override fun key(s: String) = s
    fun show(s: String) = s.chunked(3).joinToString("\n")
}

object ConnectThree : Game<List<Int>, Int> {
    // 4 columns x 3 rows, stored as column heights plus the move list encoded in the key
    const val COLS = 4
    const val ROWS = 3
    override fun moves(s: List<Int>) = (0 until COLS).filter { c -> s.count { it == c } < ROWS }
    override fun play(s: List<Int>, m: Int) = s + m
    private fun grid(s: List<Int>): Array<CharArray> {
        val g = Array(ROWS) { CharArray(COLS) { '.' } }
        val h = IntArray(COLS)
        for ((i, c) in s.withIndex()) g[ROWS - 1 - h[c]++][c] = if (i % 2 == 0) 'X' else 'O'
        return g
    }

    override fun score(s: List<Int>, maximizing: Boolean): Int? {
        val g = grid(s)
        val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val p = g[r][c]
            if (p == '.') continue
            for ((dr, dc) in dirs) {
                if ((0 until 3).all { k -> r + dr * k in 0 until ROWS && c + dc * k in 0 until COLS && g[r + dr * k][c + dc * k] == p }) {
                    return if (p == 'X') 10 - s.size else -10 + s.size
                }
            }
        }
        return if (s.size == ROWS * COLS) 0 else null
    }

    override fun key(s: List<Int>) = grid(s).joinToString("/") { String(it) }
    fun show(s: List<Int>) = grid(s).joinToString("\n") { String(it) }
}

fun main() {
    val ttt = Searcher(TicTacToe)
    println("value of the empty board (perfect play): ${ttt.value(".........", true)} after ${ttt.nodes} nodes")
    val full = Searcher(TicTacToe)
    println("without pruning but memoized: ${full.full(".........", true)} with ${full.nodes} distinct positions")
    var board = "........."
    var turn = true
    val history = ArrayList<Int>()
    while (TicTacToe.score(board, turn) == null) {
        val m = Searcher(TicTacToe).best(board, turn)
        history.add(m)
        board = TicTacToe.play(board, m)
        turn = !turn
    }
    println("self play moves: $history")
    println(TicTacToe.show(board))
    println("result: ${TicTacToe.score(board, turn)}")
    val trap = "XO.X.O..."
    println("X to move from $trap -> best ${Searcher(TicTacToe).best(trap, true)}")
    val immediate = "XX.OO...."
    println("X can win at ${Searcher(TicTacToe).best(immediate, true)}, O blocks at ${Searcher(TicTacToe).best("XX..O....", false)}")
    val c3 = Searcher(ConnectThree)
    println("connect three value: ${c3.value(emptyList(), true)} (${c3.nodes} nodes)")
    var state = emptyList<Int>()
    var t = true
    while (ConnectThree.score(state, t) == null) {
        state = ConnectThree.play(state, Searcher(ConnectThree).best(state, t))
        t = !t
    }
    println("connect three self play: $state")
    println(ConnectThree.show(state))
}
