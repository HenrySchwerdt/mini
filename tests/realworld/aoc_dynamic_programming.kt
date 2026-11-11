// Dynamic programming toolbox: LCS, edit distance, knapsack, coin change, longest increasing subsequence, partition counts.

fun lcs(a: String, b: String): String {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }
    val sb = StringBuilder()
    var i = a.length
    var j = b.length
    while (i > 0 && j > 0) {
        when {
            a[i - 1] == b[j - 1] -> {
                sb.append(a[i - 1])
                i--
                j--
            }
            dp[i - 1][j] >= dp[i][j - 1] -> i--
            else -> j--
        }
    }
    return sb.reverse().toString()
}

fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val subst = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(subst, prev[j] + 1, cur[j - 1] + 1)
        }
        prev = cur
    }
    return prev[b.length]
}

data class Item(val name: String, val weight: Int, val value: Int)

fun knapsack(items: List<Item>, capacity: Int): Pair<Int, List<Item>> {
    val best = Array(items.size + 1) { IntArray(capacity + 1) }
    for ((idx, item) in items.withIndex()) {
        for (c in 0..capacity) {
            best[idx + 1][c] = best[idx][c]
            if (item.weight <= c) best[idx + 1][c] = maxOf(best[idx + 1][c], best[idx][c - item.weight] + item.value)
        }
    }
    val chosen = ArrayList<Item>()
    var c = capacity
    for (idx in items.size downTo 1) {
        if (best[idx][c] != best[idx - 1][c]) {
            chosen.add(items[idx - 1])
            c -= items[idx - 1].weight
        }
    }
    return best[items.size][capacity] to chosen.reversed()
}

fun coinWays(coins: IntArray, target: Int): Long {
    val ways = LongArray(target + 1)
    ways[0] = 1
    for (coin in coins) for (t in coin..target) ways[t] += ways[t - coin]
    return ways[target]
}

fun minCoins(coins: IntArray, target: Int): Int {
    val inf = Int.MAX_VALUE / 2
    val best = IntArray(target + 1) { inf }
    best[0] = 0
    for (t in 1..target) for (c in coins) if (c <= t && best[t - c] + 1 < best[t]) best[t] = best[t - c] + 1
    return if (best[target] >= inf) -1 else best[target]
}

fun longestIncreasing(xs: List<Int>): List<Int> {
    val tails = ArrayList<Int>()
    val tailIdx = ArrayList<Int>()
    val parent = IntArray(xs.size) { -1 }
    for ((i, x) in xs.withIndex()) {
        var lo = 0
        var hi = tails.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (tails[mid] < x) lo = mid + 1 else hi = mid
        }
        if (lo == tails.size) {
            tails.add(x)
            tailIdx.add(i)
        } else {
            tails[lo] = x
            tailIdx[lo] = i
        }
        if (lo > 0) parent[i] = tailIdx[lo - 1]
    }
    val out = ArrayList<Int>()
    var k = tailIdx.lastOrNull() ?: -1
    while (k >= 0) {
        out.add(xs[k])
        k = parent[k]
    }
    return out.reversed()
}

fun partitions(n: Int): Long {
    val p = LongArray(n + 1)
    p[0] = 1
    for (k in 1..n) for (i in k..n) p[i] += p[i - k]
    return p[n]
}

fun pascal(rows: Int): List<List<Long>> {
    val out = ArrayList<List<Long>>()
    var row = listOf(1L)
    repeat(rows) {
        out.add(row)
        row = listOf(1L) + row.zipWithNext { a, b -> a + b } + listOf(1L)
    }
    return out
}

fun main() {
    println("lcs: " + lcs("AGGTAB", "GXTXAYB"))
    println("lcs: " + lcs("the quick brown fox", "a quick brown dog"))
    for ((a, b) in listOf("kitten" to "sitting", "flaw" to "lawn", "" to "abc", "same" to "same")) {
        println("edit($a, $b) = ${editDistance(a, b)}")
    }
    val items = listOf(Item("map", 9, 150), Item("compass", 13, 35), Item("water", 153, 200), Item("sandwich", 50, 160),
        Item("glucose", 15, 60), Item("tin", 68, 45), Item("banana", 27, 60), Item("apple", 39, 40))
    val (value, chosen) = knapsack(items, 200)
    println("knapsack: value=$value items=${chosen.joinToString { it.name }} weight=${chosen.sumOf { it.weight }}")
    println("coin ways 100: " + coinWays(intArrayOf(1, 5, 10, 25, 50), 100))
    println("min coins 63: " + minCoins(intArrayOf(1, 5, 10, 21, 25), 63))
    println("min coins 7 with 2,4: " + minCoins(intArrayOf(2, 4), 7))
    println("lis: " + longestIncreasing(listOf(10, 9, 2, 5, 3, 7, 101, 18, 4, 20, 21)))
    println("partitions: " + (1..10).map { partitions(it * 10) })
    pascal(7).forEach { row -> println(row.joinToString(" ").padStart(18 + row.size)) }
}
