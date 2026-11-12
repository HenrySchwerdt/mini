// A prefix tree with autocomplete: insertion with counts, prefix search, ranked suggestions, deletion, longest common prefix,
// word ladder neighbours, and a simple spell checker based on edit distance.

class TrieNode {
    val children = sortedMapOf<Char, TrieNode>()
    var terminal = false
    var count = 0
}

class Trie {
    private val root = TrieNode()
    var size = 0
        private set

    fun insert(word: String, times: Int = 1) {
        var n = root
        for (c in word) n = n.children.getOrPut(c) { TrieNode() }
        if (!n.terminal) size++
        n.terminal = true
        n.count += times
    }

    private fun find(prefix: String): TrieNode? {
        var n: TrieNode? = root
        for (c in prefix) n = n?.children?.get(c)
        return n
    }

    operator fun contains(word: String) = find(word)?.terminal == true
    fun count(word: String) = find(word)?.takeIf { it.terminal }?.count ?: 0

    fun withPrefix(prefix: String): List<String> {
        val out = ArrayList<String>()
        fun walk(n: TrieNode, acc: StringBuilder) {
            if (n.terminal) out.add(acc.toString())
            for ((c, child) in n.children) {
                acc.append(c)
                walk(child, acc)
                acc.deleteCharAt(acc.length - 1)
            }
        }
        val start = find(prefix) ?: return emptyList()
        walk(start, StringBuilder(prefix))
        return out
    }

    fun suggest(prefix: String, limit: Int): List<Pair<String, Int>> =
        withPrefix(prefix).map { it to count(it) }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(limit)

    fun remove(word: String): Boolean {
        val path = ArrayList<Pair<TrieNode, Char>>()
        var n = root
        for (c in word) {
            path.add(n to c)
            n = n.children[c] ?: return false
        }
        if (!n.terminal) return false
        n.terminal = false
        n.count = 0
        size--
        for ((parent, c) in path.asReversed()) {
            val child = parent.children.getValue(c)
            if (child.terminal || child.children.isNotEmpty()) break
            parent.children.remove(c)
        }
        return true
    }

    fun longestCommonPrefix(): String {
        val sb = StringBuilder()
        var n = root
        while (n.children.size == 1 && !n.terminal) {
            val (c, child) = n.children.entries.first()
            sb.append(c)
            n = child
        }
        return sb.toString()
    }

    fun nodeCount(): Int {
        fun go(n: TrieNode): Int = 1 + n.children.values.sumOf { go(it) }
        return go(root)
    }
}

fun editDistance(a: String, b: String): Int {
    val dp = Array(a.length + 1) { IntArray(b.length + 1) { if (it == 0) 0 else it } }
    for (i in 0..a.length) dp[i][0] = i
    for (i in 1..a.length) for (j in 1..b.length) {
        dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
    }
    return dp[a.length][b.length]
}

fun main() {
    val trie = Trie()
    val corpus = "the quick brown fox jumps over the lazy dog then the dog barks and the fox runs away quickly through the thick forest".split(" ")
    corpus.forEach { trie.insert(it) }
    println("words: ${trie.size}, nodes: ${trie.nodeCount()}")
    println("th*: " + trie.withPrefix("th"))
    println("suggest 'th': " + trie.suggest("th", 3))
    println("suggest 'q': " + trie.suggest("q", 5))
    println("contains: " + listOf("fox", "fo", "foxes", "the").map { it in trie })
    println("count(the)=${trie.count("the")} count(cat)=${trie.count("cat")}")
    println("remove dog: ${trie.remove("dog")}, again: ${trie.remove("dog")}, nodes: ${trie.nodeCount()}")
    println("d*: " + trie.withPrefix("d"))
    val t2 = Trie()
    listOf("interview", "internet", "internal", "interval").forEach { t2.insert(it) }
    println("lcp: ${t2.longestCommonPrefix()}")
    val dict = trie.withPrefix("")
    for (typo in listOf("quikc", "brwon", "lasy", "forst", "xyz")) {
        val best = dict.map { it to editDistance(typo, it) }.minWithOrNull(compareBy({ it.second }, { it.first }))!!
        println("$typo -> ${if (best.second <= 2) best.first else "(no suggestion)"} (distance ${best.second})")
    }
    val weights = Trie()
    listOf("apple" to 5, "app" to 9, "apply" to 2, "apt" to 7, "bat" to 1).forEach { (w, n) -> weights.insert(w, n) }
    println(weights.suggest("ap", 3))
    println(weights.suggest("", 10).map { it.first })
}
