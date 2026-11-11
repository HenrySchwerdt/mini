// An extension-heavy utility toolkit: extension functions and properties on standard types, infix and operator extensions,
// scope functions, generic extensions with receivers, Result handling, tailrec, and small functional helpers.

import kotlin.math.max
import kotlin.math.min

// --- strings ----------------------------------------------------------------------------------------------------------

fun String.capitalizeWords(): String = split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
fun String.camelToSnake(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
fun String.snakeToCamel(): String = split('_').mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }.joinToString("")
fun String.isPalindrome(): Boolean = filter { it.isLetterOrDigit() }.lowercase().let { it == it.reversed() }
fun String.truncate(n: Int, ellipsis: String = "..."): String = if (length <= n) this else take(max(0, n - ellipsis.length)) + ellipsis
fun String.indentLines(n: Int): String = lines().joinToString("\n") { " ".repeat(n) + it }
fun String.countOf(sub: String): Int = windowed(sub.length).count { it == sub }
val String.vowels: Int get() = count { it in "aeiouAEIOU" }
infix fun String.repeatedBy(n: Int) = repeat(n)
operator fun String.times(n: Int) = repeat(n)
operator fun String.rem(args: List<Any>): String {
    var i = 0
    return Regex("%s").replace(this) { args.getOrNull(i++)?.toString() ?: "?" }
}

// --- numbers ----------------------------------------------------------------------------------------------------------

fun Int.isEven() = this % 2 == 0
fun Int.factorial(): Long = if (this <= 1) 1L else (2..this).fold(1L) { a, b -> a * b }
infix fun Int.pow(e: Int): Long = (1..e).fold(1L) { a, _ -> a * this }
fun Long.toBinary(): String = if (this == 0L) "0" else generateSequence(this) { if (it > 1) it / 2 else null }.map { it % 2 }.toList().reversed().joinToString("")
val Int.digits: List<Int> get() = toString().map { it - '0' }
fun Int.clampTo(range: IntRange) = max(range.first, min(range.last, this))
tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
fun Double.roundTo(places: Int): Double {
    var f = 1.0
    repeat(places) { f *= 10 }
    return kotlin.math.round(this * f) / f
}
fun Int.toRoman(): String {
    val numerals = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
    var n = this
    val sb = StringBuilder()
    for ((v, s) in numerals) while (n >= v) {
        sb.append(s)
        n -= v
    }
    return sb.toString()
}

// --- collections ------------------------------------------------------------------------------------------------------

fun <T> List<T>.second(): T = this[1]
fun <T> List<T>.secondOrNull(): T? = getOrNull(1)
fun <T> Iterable<T>.frequencies(): Map<T, Int> = groupingBy { it }.eachCount()
fun <T : Comparable<T>> Iterable<T>.median(): T? = sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
fun <K, V> Map<K, V>.invert(): Map<V, List<K>> = entries.groupBy({ it.value }, { it.key })
fun <T> List<T>.rotate(n: Int): List<T> = if (isEmpty()) this else drop(n.mod(size)) + take(n.mod(size))
fun <T> List<T>.permutations(): List<List<T>> = if (size <= 1) listOf(this) else indices.flatMap { i -> (take(i) + drop(i + 1)).permutations().map { listOf(this[i]) + it } }
fun <T> List<T>.subsets(): List<List<T>> = fold(listOf(emptyList())) { acc, x -> acc + acc.map { it + x } }
fun <A, B> Pair<A, B>.swap() = second to first
fun <T> T.also2(block: (T) -> Unit): T { block(this); return this }
fun <T, R> T.pipe(f: (T) -> R): R = f(this)
operator fun <T> List<T>.get(range: IntRange): List<T> = slice(range)
fun IntArray.prefixSums(): IntArray = runningFold(0) { a, b -> a + b }.toIntArray()
fun <T> Collection<T>.sampleEvery(n: Int): List<T> = filterIndexed { i, _ -> i % n == 0 }

class Version(val parts: List<Int>) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        for (i in 0 until max(parts.size, other.parts.size)) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString() = parts.joinToString(".")
}

fun String.toVersion() = Version(split('.').map { it.toInt() })

fun parseAge(s: String): Result<Int> = runCatching { s.trim().toInt() }.mapCatching { require(it in 0..150) { "age out of range: $it" }; it }

fun main() {
    println("hello wide world".capitalizeWords())
    println("parseHttpResponseCode".camelToSnake() + " " + "max_retry_count".snakeToCamel())
    println(listOf("A man, a plan, a canal: Panama", "hello").map { it.isPalindrome() })
    println("The quick brown fox".truncate(12) + " | " + "short".truncate(12))
    println("a\nb".indentLines(2).replace(" ", "."))
    println("banana".countOf("ana") to "Education".vowels)
    println(("ab" repeatedBy 3) + " " + "xy" * 2)
    println("%s + %s = %s" % listOf(1, 2, 3) + " / " + "%s %s" % listOf("only"))
    println(5.isEven().toString() + " " + 10.factorial() + " " + (2 pow 40) + " " + 37L.toBinary() + " " + 90210.digits)
    println(15.clampTo(1..10).toString() + " " + (-3).clampTo(1..10) + " " + gcd(1071, 462) + " " + 3.14159.roundTo(2))
    println(listOf(1994, 2024, 49, 3999).map { it.toRoman() })
    println(listOf(1, 2, 3).second().toString() + " " + emptyList<Int>().secondOrNull() + " " + "mississippi".toList().frequencies())
    println(listOf(5, 3, 9, 1, 7).median().toString() + " " + mapOf("a" to 1, "b" to 2, "c" to 1).invert())
    println(listOf(1, 2, 3, 4, 5).rotate(2).toString() + " " + listOf(1, 2, 3, 4, 5).rotate(-1))
    println(listOf(1, 2, 3).permutations())
    println(listOf('a', 'b', 'c').subsets().map { it.joinToString("") })
    println((1 to "one").swap().toString() + " " + 21.pipe { it * 2 } + " " + "x".also2 { print("[also] ") })
    println(listOf(10, 20, 30, 40, 50)[1..3].toString() + " " + intArrayOf(1, 2, 3, 4).prefixSums().toList() + " " + (1..10).toList().sampleEvery(4))
    println(listOf("1.10.0", "1.2", "1.2.3", "0.9.9").map { it.toVersion() }.sorted().joinToString(" < "))
    for (s in listOf("42", " 7 ", "abc", "200")) {
        val r = parseAge(s)
        println("'$s' -> " + r.fold({ "ok $it" }, { "error ${it::class.simpleName}: ${it.message}" }))
    }
    val result = StringBuilder().apply {
        append("built")
        append(' ')
        append(42)
    }.let { it.toString().uppercase() }
    println(result)
    val nums = mutableListOf(3, 1, 2).apply { sort(); add(10) }.also { println("sorted: $it") }.map { it * 2 }
    println(nums)
    println(with(nums) { "size=$size sum=${sum()} max=${max()}" })
    val config = object {
        val name = "cfg"
        fun describe() = "config $name"
    }
    println(config.describe())
}
