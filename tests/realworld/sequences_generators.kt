// Lazy sequences and generators: sequence { yield }, infinite sequences, windowed pipelines, generateSequence, iterators,
// primes, hailstones, merge of sorted streams, running aggregates and a tokenizing generator.

fun primes(): Sequence<Int> = sequence {
    val found = ArrayList<Int>()
    var n = 2
    while (true) {
        if (found.none { n % it == 0 }) {
            found.add(n)
            yield(n)
        }
        n++
    }
}

fun fibonacci(): Sequence<Long> = generateSequence(0L to 1L) { (a, b) -> b to a + b }.map { it.first }

fun hailstone(start: Int): Sequence<Int> = generateSequence(start) { if (it == 1) null else if (it % 2 == 0) it / 2 else 3 * it + 1 }

fun <T : Comparable<T>> merge(a: Sequence<T>, b: Sequence<T>): Sequence<T> = sequence {
    val ia = a.iterator()
    val ib = b.iterator()
    var x = if (ia.hasNext()) ia.next() else null
    var y = if (ib.hasNext()) ib.next() else null
    while (x != null || y != null) {
        if (y == null || (x != null && x <= y)) {
            yield(x!!)
            x = if (ia.hasNext()) ia.next() else null
        } else {
            yield(y)
            y = if (ib.hasNext()) ib.next() else null
        }
    }
}

fun <T> Sequence<T>.everyNth(n: Int): Sequence<T> = withIndex().filter { it.index % n == 0 }.map { it.value }

fun <T> Sequence<T>.pairwise(): Sequence<Pair<T, T>> = sequence {
    var prev: T? = null
    var first = true
    for (x in this@pairwise) {
        if (!first) yield(prev!! to x)
        prev = x
        first = false
    }
}

fun tokens(text: String): Sequence<String> = sequence {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isWhitespace() -> i++
            c.isDigit() -> {
                val s = i
                while (i < text.length && text[i].isDigit()) i++
                yield(text.substring(s, i))
            }
            c.isLetter() -> {
                val s = i
                while (i < text.length && text[i].isLetterOrDigit()) i++
                yield(text.substring(s, i))
            }
            else -> {
                yield(c.toString())
                i++
            }
        }
    }
}

class Countdown(private val from: Int) : Iterable<Int> {
    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var cur = from
        override fun hasNext() = cur > 0
        override fun next(): Int = cur--
    }
}

fun main() {
    println("primes: " + primes().take(15).toList())
    println("primes > 100: " + primes().dropWhile { it < 100 }.take(5).toList())
    println("twin primes: " + primes().take(40).toList().zipWithNext().filter { (a, b) -> b - a == 2 }.take(6))
    println("fib: " + fibonacci().take(15).toList())
    println("fib(80) = " + fibonacci().drop(80).first())
    println("even fib sum below 4M: " + fibonacci().takeWhile { it < 4_000_000 }.filter { it % 2 == 0L }.sum())
    val h = hailstone(27).toList()
    println("hailstone(27): ${h.size} steps, max ${h.max()}, head ${h.take(8)}")
    println("longest hailstone under 30: " + (1 until 30).maxByOrNull { hailstone(it).count() })
    println("merged: " + merge(primes().take(8), sequenceOf(1, 4, 6, 9, 20)).toList())
    println("every 3rd: " + (1..20).asSequence().everyNth(3).toList())
    println("pairs: " + sequenceOf("a", "b", "c", "d").pairwise().map { "${it.first}${it.second}" }.toList())
    println("tokens: " + tokens("let x1 = 42+(y*7);").toList())
    println("running sum: " + (1..8).asSequence().runningReduce { a, b -> a + b }.toList())
    println("chunked: " + (1..10).asSequence().chunked(4).map { it.sum() }.toList())
    println("windowed: " + (1..6).asSequence().windowed(3, 2).toList())
    println("sorted distinct: " + sequenceOf(5, 3, 5, 1, 3).distinct().sorted().toList())
    println("flatMap: " + (1..4).asSequence().flatMap { n -> (1..n).asSequence().map { n * 10 + it } }.toList())
    println("countdown: " + Countdown(5).toList() + " sum=" + Countdown(100).sum())
    val lazyLog = ArrayList<String>()
    val result = (1..10).asSequence().onEach { lazyLog.add("a$it") }.filter { it % 3 == 0 }.onEach { lazyLog.add("b$it") }.map { it * it }.first()
    println("first = $result, evaluated: $lazyLog")
    val it = sequence {
        yield(1)
        yieldAll(listOf(2, 3))
        yieldAll(sequenceOf(4, 5))
    }.iterator()
    val got = ArrayList<Int>()
    while (it.hasNext()) got.add(it.next())
    println("yieldAll: $got")
    val grouped = (1..20).asSequence().groupBy { it % 3 }
    println("grouped: $grouped")
    println("zip: " + (1..3).asSequence().zip(sequenceOf("x", "y", "z", "w")).toList())
    println("unzip: " + listOf(1 to "a", 2 to "b").unzip())
    println("generateSequence: " + generateSequence(1) { if (it < 200) it * 3 else null }.toList())
    println("constrain: " + generateSequence(1) { it * 2 }.constrainOnce().take(5).toList())
}
