package streamk

open class StreamException(message: String, val position: Int) : Exception(message)
class ParseFailure(message: String, position: Int, val expected: List<String>) : StreamException(message, position)

class Resource(val name: String, private val journal: MutableList<String>) : AutoCloseable {
    init { journal.add("open " + name) }
    override fun close() { journal.add("close " + name) }
    fun read(): String = "data of " + name
}

fun <T> withResources(journal: MutableList<String>, vararg names: String, block: (List<Resource>) -> T): T {
    val opened = ArrayList<Resource>()
    try {
        for (n in names) opened.add(Resource(n, journal))
        return block(opened)
    } finally {
        for (r in opened.asReversed()) r.close()
    }
}

fun primes(): Sequence<Int> = sequence {
    var n = 2
    while (true) {
        var isPrime = true
        var d = 2
        while (d * d <= n) {
            if (n % d == 0) { isPrime = false; break }
            d++
        }
        if (isPrime) yield(n)
        n++
    }
}

fun <T> Sequence<T>.chunkedBy(pred: (T) -> Boolean): Sequence<List<T>> = sequence {
    var cur = ArrayList<T>()
    for (x in this@chunkedBy) {
        if (pred(x) && cur.isNotEmpty()) {
            yield(cur)
            cur = ArrayList()
        }
        cur.add(x)
    }
    if (cur.isNotEmpty()) yield(cur)
}

fun <A, B, R> Sequence<A>.zipWith(other: Sequence<B>, f: (A, B) -> R): Sequence<R> = zip(other).map { (a, b) -> f(a, b) }

class Window<T>(private val size: Int) : Iterable<List<T>> {
    private val items = ArrayList<T>()
    fun push(x: T): Window<T> { items.add(x); return this }
    override fun iterator(): Iterator<List<T>> = object : Iterator<List<T>> {
        var i = 0
        override fun hasNext() = i + size <= items.size
        override fun next(): List<T> = items.subList(i, i + size).also { i++ }
    }
}

enum class Token(val symbol: String) {
    PLUS("+"), MINUS("-"), TIMES("*");

    companion object {
        fun of(c: Char): Token = entries.firstOrNull { it.symbol[0] == c } ?: throw ParseFailure("unexpected '" + c + "'", -1, entries.map { it.symbol })
    }
}

fun evalRpn(text: String): Int {
    val stack = ArrayList<Int>()
    var pos = 0
    for (part in text.split(' ')) {
        val n = part.toIntOrNull()
        if (n != null) {
            stack.add(n)
        } else {
            if (stack.size < 2) throw StreamException("stack underflow at " + part, pos)
            val b = stack.removeAt(stack.size - 1)
            val a = stack.removeAt(stack.size - 1)
            val t = try { Token.of(part[0]) } catch (e: ParseFailure) { throw ParseFailure(e.message!!, pos, e.expected) }
            stack.add(when (t) { Token.PLUS -> a + b; Token.MINUS -> a - b; Token.TIMES -> a * b })
        }
        pos += part.length + 1
    }
    return stack.single()
}

suspend fun SequenceScope<String>.emitTwice(s: String) {
    yield(s)
    yield(s)
}

fun twice(vararg items: String): Sequence<String> = sequence { for (i in items) emitTwice(i) }

val wordPattern = Regex("[a-z]+")

fun words(text: String): List<String> = wordPattern.findAll(text.lowercase()).map { it.value }.toList()

inline fun <T, K> Iterable<T>.histogram(key: (T) -> K): Map<K, Int> {
    val m = LinkedHashMap<K, Int>()
    for (x in this) m[key(x)] = (m[key(x)] ?: 0) + 1
    return m
}
