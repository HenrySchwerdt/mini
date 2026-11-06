// Double.fromBits / Float.fromBits, Char constants, Float math, ranges of floats and comparables, coerce, comparisons, lazy,
// kotlin.system, Random extras, Duration extras, Throwable helpers and the string long tail.
import kotlin.comparisons.*
import kotlin.math.*
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.random.nextLong
import kotlin.random.nextUInt
import kotlin.random.nextULong
import kotlin.system.measureTimeMillis
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

fun main() {
    // bit casts
    println(Double.fromBits(1.0.toBits()))
    println(Double.fromBits(4611686018427387904L))
    println(Float.fromBits(1.5f.toBits()))
    println(Float.fromBits(0x7fc00000).isNaN())
    println(Double.fromBits(Double.NaN.toBits()).isNaN())
    println(Float.fromBits(-1082130432))

    // Char constants
    println(Char.MIN_HIGH_SURROGATE.code)
    println(Char.MAX_HIGH_SURROGATE.code)
    println(Char.MIN_LOW_SURROGATE.code)
    println(Char.MAX_LOW_SURROGATE.code)
    println(Char.MIN_SURROGATE == Char.MIN_HIGH_SURROGATE)
    println(Char.MAX_SURROGATE.code)
    println(Char.SIZE_BITS + Char.SIZE_BYTES)

    // Float math
    println(tan(0f))
    println(atan2(1f, 1f))
    println(hypot(3f, 4f))
    println(log2(8f))
    println(log10(1000f))
    println(log(8f, 2f))
    println(sinh(0f))
    println(1f.nextUp() > 1f)
    println(1f.nextDown() < 1f)
    println(1.0.ulp)
    println(1.0f.ulp)
    println(1.5f.withSign(-1f))
    println((-2.5f).withSign(1f))
    println((-5.5f).mod(2f))
    println(5.5f.mod(-2f))
    println(0.0.nextUp() == Double.MIN_VALUE)

    // ranges
    val fr = 1.0f..3.0f
    println(fr)
    println(2.5f in fr)
    println(fr.isEmpty())
    val dr = 1.0..3.0
    println(dr.start + dr.endInclusive)
    println(dr.contains(1.5f))
    println(dr.lessThanOrEquals(2.0, 1.0))
    println((1.0..0.0).isEmpty())
    println((0.0..1.0) == (0.0..1.0))
    println((1.0..0.0) == (5.0..2.0))
    println(Double.NaN in 0.0..1.0)
    val sr = "b".."d"
    println("c" in sr)
    println("e" in sr)
    println(sr)
    println(sr.isEmpty())
    println(("a"..<"c").contains("b"))
    println((1.0..<2.0).contains(2.0))
    println(5.5.coerceIn(1.0..3.0))
    println(0.5f.coerceIn(1.0f..3.0f))
    println(7.coerceIn(1..3))
    println(2.coerceIn(1..3))
    println(5L.coerceIn(1L..3L))
    println(2.5.coerceIn(1.0..3.0))
    println("z".coerceIn("a".."m"))
    println("z".coerceAtMost("m"))
    println("a".coerceAtLeast("m"))
    println("q".coerceIn("a", "m"))
    println(2.5f.coerceAtLeast(3f))
    println(9f.coerceAtMost(3f))
    println(9f.coerceIn(1f, 3f))
    println(200.toByte().coerceIn(1.toByte(), 100.toByte()))
    println(300.toShort().coerceIn(1.toShort(), 100.toShort()))
    println(10u.coerceIn(1u, 5u))
    println(10uL.coerceAtLeast(20uL))
    try {
        5.coerceIn(3..1)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        5.0.coerceIn(3.0..1.0)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        5f.coerceIn(3f, 1f)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }

    // comparisons
    println(maxOf(1, 5, 3, 4))
    println(minOf(7, 5, 3, 4))
    println(maxOf(1.0, 2.0, 3.0))
    println(minOf(1.5f, 0.5f, 3f))
    println(maxOf(Double.NaN, 1.0).isNaN())
    println(minOf(1.0, Double.NaN).isNaN())
    println(maxOf(-0.0, 0.0))
    println(minOf(0.0, -0.0))
    println(maxOf("a", "c", "b"))
    println(minOf("b", "c", "a", "d"))
    println(maxOf("aa", "b", compareBy<String> { it.length }))
    println(minOf("aa", "b", "ccc", comparator = compareBy<String> { it.length }))
    println(maxOf(3L, 9L, 5L, 7L))
    println(compareValuesBy("apple", "fig") { it.length })
    println(compareValuesBy("apple", "fig", reverseOrder<Int>()) { it.length })
    println(listOf(3, null, 1, 2).sortedWith(nullsFirst()))
    println(listOf(3, null, 1, 2).sortedWith(nullsLast(reverseOrder())))
    println(listOf("b", "A", "c").sortedWith(String.CASE_INSENSITIVE_ORDER))
    println(String.CASE_INSENSITIVE_ORDER.compare("a", "B") < 0)
    println(listOf("bb", "a", "cc").sortedWith(compareBy(reverseOrder<Int>()) { it.length }))

    // lazy, hashCode of nullables
    var computed = 0
    val l = lazy(LazyThreadSafetyMode.NONE) { computed++; 42 }
    println(l.isInitialized())
    println(l.value + l.value)
    println(computed)
    println(lazyOf("x").value)
    println(lazyOf(5).isInitialized())
    val maybe: Int? = null
    println(maybe.hashCode())
    val b: Boolean? = true
    println(b.hashCode())
    println(true.hashCode())
    println(false.hashCode())

    // kotlin.system
    println(measureTimeMillis { }.let { it >= 0 })

    // Random extras
    val rnd = Random(7)
    println(rnd.nextBytes(5).size)
    println(rnd.nextBytes(ByteArray(8), 2, 6).size)
    println(rnd.nextInt(5..5))
    println(rnd.nextInt(10..12) in 10..12)
    println(rnd.nextLong(3L..3L))
    println(rnd.nextUInt(5u) < 5u)
    println(rnd.nextUInt(5u, 9u) in 5u..8u)
    println(rnd.nextULong(5uL, 9uL) in 5uL..8uL)
    try {
        rnd.nextInt(5..1)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        rnd.nextUInt(5u, 5u)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    try {
        rnd.nextBytes(ByteArray(2), 1, 0)
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }

    // Duration extras
    println((1.hours + 2.minutes + 3.seconds + 4.milliseconds).toComponents { h, m, s, ns -> "$h $m $s $ns" })
    println((90.minutes).toComponents { m, s, ns -> "$m $s $ns" })
    println((90.seconds).toComponents { s, ns -> "$s $ns" })
    println(1.hours.toIsoString())
    println((1.hours + 30.minutes).toIsoString())
    println(0.seconds.toIsoString())
    println(1500.milliseconds.toIsoString())
    println((-90).seconds.toIsoString())
    println(listOf(1.seconds, 2.seconds, 500.milliseconds).sum())
    println(sequenceOf(1.minutes, 1.minutes).sum())

    // Throwable helpers
    val ex = IllegalStateException("outer", RuntimeException("inner"))
    println(ex.stackTraceToString().lines().first())
    println(ex.stackTraceToString().contains("Caused by"))
    ex.addSuppressed(Exception("s1"))
    println(ex.suppressedExceptions.size)
    try {
        throw ConcurrentModificationException("cme")
    } catch (e: RuntimeException) {
        println(e.message)
    }

    // strings and numbers
    println("a-b--c".trim('-', 'a'))
    println("--ab--".trimStart('-'))
    println("--ab--".trimEnd('-'))
    println("  xx ".trimStart { it == ' ' })
    println("abcabc".lastIndexOfAny(charArrayOf('b', 'c')))
    println("abcabc".lastIndexOfAny(listOf("ab", "ca")))
    println("abcabc".findLastAnyOf(listOf("ab", "ca")))
    println("abcdef".toCharArray(2, 4).concatToString())
    println("abcdef".encodeToByteArray(1, 3).size)
    println("12".toByte() + "-5".toShort())
    println("7f".toInt(16))
    println("ff".toLong(16))
    println("200".toByteOrNull())
    println("100".toByteOrNull())
    println("70000".toShortOrNull())
    println("true".toBooleanStrict())
    try {
        "TRUE".toBooleanStrict()
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
    println("255".toUByte())
    println("256".toUByteOrNull())
    println("ffff".toUShort(16))
    println("4294967295".toUInt())
    println("4294967296".toUIntOrNull())
    println("ff".toUInt(16))
    println("18446744073709551615".toULong())
    println("+5".toULongOrNull(10))
    println("-5".toULongOrNull(10))
    println("zz".toULongOrNull(36))
    println("abcdefg".chunkedSequence(3).toList())
    println("abcdefg".windowedSequence(3, 2, true) { it.length }.toList())
    println("hello".runningFold("") { acc, c -> acc + c })
    println("hello".runningReduce { a, b -> maxOf(a, b) })
    println("hello".maxBy { it.code })
    println("hello".minOf { it.code })
    println("hello".sumOf { it.code.toLong() })
    println("hello".foldRight("") { c, acc -> acc + c })
    println("hello".reduceIndexed { i, a, c -> if (i == 2) c else a })
    println("hello".firstNotNullOfOrNull { if (it > 'h') it else null })
    println("hello".mapTo(ArrayList()) { it.uppercaseChar() })
    println("hello".filterTo(StringBuilder()) { it != 'l' })
    println("hello".toCollection(ArrayList()).size)
    println(StringBuilder("abc" as CharSequence).append("d"))
    println("hello".random() in "hello")
    println(listOf(1, 2, 3).iterator().let { it.next(); it.hasNext() })
    val ml = mutableListOf(1, 2, 3, 4)
    val mit = ml.iterator()
    while (mit.hasNext()) if (mit.next() % 2 == 0) mit.remove()
    println(ml)
    println(HashMap<String, Int>(16, 0.75f).size)
    println(LinkedHashMap<String, Int>(16, 0.75f).apply { put("k", 1) })
    println(LinkedHashSet<Int>(16, 0.75f).apply { add(3) })
}
