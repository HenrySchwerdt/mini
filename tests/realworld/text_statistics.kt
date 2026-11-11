// Text statistics: word frequencies, n-grams, readability numbers, longest words per initial, with groupBy / fold / sequences /
// windowed / zip and friends.

val TEXT = """
It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness,
it was the epoch of belief, it was the epoch of incredulity, it was the season of Light, it was the season of Darkness,
it was the spring of hope, it was the winter of despair, we had everything before us, we had nothing before us,
we were all going direct to Heaven, we were all going direct the other way.
""".trimIndent()

fun words(text: String): List<String> = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }

fun histogram(counts: Map<String, Int>, limit: Int): String {
    val top = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit)
    val width = top.maxOf { it.key.length }
    return top.joinToString("\n") { (word, n) -> "${word.padEnd(width)} ${"#".repeat(n)} $n" }
}

fun ngrams(ws: List<String>, n: Int): Map<String, Int> =
    ws.windowed(n).map { it.joinToString(" ") }.groupingBy { it }.eachCount()

fun syllables(word: String): Int {
    var count = 0
    var prevVowel = false
    for (c in word) {
        val v = c in "aeiouy"
        if (v && !prevVowel) count++
        prevVowel = v
    }
    if (word.endsWith("e") && count > 1) count--
    return maxOf(count, 1)
}

fun main() {
    val ws = words(TEXT)
    println("words: ${ws.size}, distinct: ${ws.toSet().size}")
    val freq = ws.groupingBy { it }.eachCount()
    println(histogram(freq, 8))
    println()
    println("bigrams:")
    println(histogram(ngrams(ws, 2), 5))
    println("trigrams: " + ngrams(ws, 3).filter { it.value >= 3 }.keys.sorted())
    println()
    val sentences = TEXT.split(Regex("(?<=[.!?])\\s+|\\n")).filter { it.isNotBlank() }
    println("lines: ${sentences.size}, avg words per line: ${"%.2f".format(ws.size.toDouble() / sentences.size)}")
    val longest = ws.distinct().sortedWith(compareByDescending<String> { it.length }.thenBy { it }).take(3)
    println("longest words: $longest")
    val byInitial = ws.distinct().groupBy { it.first() }.toSortedMap()
    println("initials: " + byInitial.entries.joinToString(" ") { "${it.key}=${it.value.size}" })
    val lengths = ws.fold(IntArray(12)) { acc, w -> acc[minOf(w.length, 11)]++; acc }
    println("length distribution: " + lengths.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:${it.value}" })
    val totalSyll = ws.sumOf { syllables(it) }
    val flesch = 206.835 - 1.015 * ws.size / sentences.size - 84.6 * totalSyll / ws.size
    println("syllables: $totalSyll, flesch reading ease: ${"%.1f".format(flesch)}")
    val letters = TEXT.lowercase().filter { it in 'a'..'z' }.groupingBy { it }.eachCount()
    println("top letters: " + letters.entries.sortedWith(compareByDescending<Map.Entry<Char, Int>> { it.value }.thenBy { it.key }).take(5).joinToString { "${it.key}${it.value}" })
    val firstRepeat = ws.asSequence().runningFold(emptySet<String>()) { seen, w -> seen + w }.zip(ws.asSequence()).firstOrNull { (seen, w) -> w in seen }
    println("first repeated word: ${firstRepeat?.second}")
    val pairs = ws.zipWithNext().count { (a, b) -> a == b }
    println("immediate repeats: $pairs")
    val positions = ws.withIndex().filter { it.value == "season" }.map { it.index }
    println("'season' at $positions")
    val caps = TEXT.split(Regex("\\W+")).filter { it.isNotEmpty() && it[0].isUpperCase() }
    println("capitalised: $caps")
    println(ws.partition { it.length > 4 }.let { (long, short) -> "${long.size} long / ${short.size} short" })
    val palin = ws.distinct().filter { it.length > 2 && it == it.reversed() }
    println("palindromes: $palin")
    println(ws.chunked(10).map { it.size })
    println(TEXT.lines().map { it.length }.average().let { "%.3f".format(it) })
}
