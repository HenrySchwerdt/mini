package collx

/** Pairs every element with the next one: [a, b, c] -> [(a, b), (b, c)]. */
fun <T> List<T>.pairwise(): List<Pair<T, T>> = if (size < 2) emptyList() else (0 until size - 1).map { this[it] to this[it + 1] }

/** Splits a list at every element where [isSeparator] holds (the separators are dropped). */
inline fun <T> List<T>.splitBy(isSeparator: (T) -> Boolean): List<List<T>> {
    val out = ArrayList<List<T>>()
    var cur = ArrayList<T>()
    for (x in this) {
        if (isSeparator(x)) {
            out.add(cur)
            cur = ArrayList()
        } else cur.add(x)
    }
    out.add(cur)
    return out
}

/** The elements that occur more than once, in order of their second occurrence. */
fun <T> Iterable<T>.duplicates(): List<T> {
    val seen = HashSet<T>()
    val dup = LinkedHashSet<T>()
    for (x in this) if (!seen.add(x)) dup.add(x)
    return dup.toList()
}

inline fun <reified T> Iterable<*>.firstOfType(): T? {
    for (x in this) if (x is T) return x
    return null
}

inline fun <T, R : Comparable<R>> Iterable<T>.maxesBy(selector: (T) -> R): List<T> {
    var best: R? = null
    val out = ArrayList<T>()
    for (x in this) {
        val v = selector(x)
        val b = best
        if (b == null || v > b) {
            best = v
            out.clear()
            out.add(x)
        } else if (v == b) out.add(x)
    }
    return out
}

fun <A, B, C> ((A) -> B).then(next: (B) -> C): (A) -> C = { a -> next(this(a)) }

fun <T> memoize(f: (T) -> T): (T) -> T {
    val cache = HashMap<T, T>()
    return { x -> cache.getOrPut(x) { f(x) } }
}

internal fun secretTag(n: Int) = "tag-$n"

/** The public face of an internal helper. */
fun tagged(n: Int): String = secretTag(n)

class Counter<T> {
    private val counts = HashMap<T, Int>()
    fun add(x: T, n: Int = 1) {
        counts[x] = (counts[x] ?: 0) + n
    }

    fun top(n: Int): List<Pair<T, Int>> = counts.entries.sortedWith(compareByDescending<Map.Entry<T, Int>> { it.value }.thenBy { it.key.toString() }).take(n).map { it.key to it.value }
    operator fun get(x: T): Int = counts[x] ?: 0
    val total: Int get() = counts.values.sum()
}
