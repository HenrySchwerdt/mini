import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class MiscTest {
    @Test
    fun randomIsDeterministicPerSeed() {
        val a = Random(42)
        val b = Random(42)
        var i = 0
        while (i < 100) {
            assertEquals(a.nextInt(), b.nextInt())
            i++
        }
        assertEquals(Random(7).nextLong(), Random(7).nextLong())
        assertNotEquals(Random(1).nextInt(), Random(2).nextInt())
    }

    @Test
    fun randomRanges() {
        val r = Random(123)
        var i = 0
        while (i < 1000) {
            val v = r.nextInt(5, 10)
            assertTrue(v >= 5 && v < 10)
            val d = r.nextDouble()
            assertTrue(d >= 0.0 && d < 1.0)
            val l = r.nextLong(100L)
            assertTrue(l >= 0L && l < 100L)
            val f = r.nextFloat()
            assertTrue(f >= 0.0f && f < 1.0f)
            assertTrue(r.nextInt(8) in 0..7)
            i++
        }
        assertFailsWith<IllegalArgumentException> { r.nextInt(5, 5) }
        var trues = 0
        i = 0
        while (i < 1000) {
            if (r.nextBoolean()) trues++
            i++
        }
        assertTrue(trues > 400 && trues < 600)
    }

    @Test
    fun randomCollections() {
        val l = (1..10).toList()
        val s1 = l.shuffled(Random(5))
        val s2 = l.shuffled(Random(5))
        assertEquals(s1, s2)
        assertEquals(l, s1.sorted())
        assertTrue(l.random(Random(1)) in l)
        assertTrue((1..3).random(Random(9)) in 1..3)
        assertNull(emptyList<Int>().randomOrNull())
        val m = l.toMutableList()
        m.shuffle(Random(3))
        assertEquals(l, m.sorted())
    }

    @Test
    fun durationFormatting() {
        assertEquals("1.5s", 1.5.seconds.toString())
        assertEquals("1h 30m", 90.minutes.toString())
        assertEquals("1.234s", 1234.milliseconds.toString())
        assertEquals("0s", 0.seconds.toString())
        assertEquals("500us", 500.microseconds.toString())
        assertEquals("45ns", 45.nanoseconds.toString())
        assertEquals("2d 3h 4m 5s", (2.days + 3.hours + 4.minutes + 5.seconds).toString())
        assertEquals("-(1h 30m)", (-90).minutes.toString())
        assertEquals("-5s", (-5).seconds.toString())
        assertEquals("Infinity", Duration.INFINITE.toString())
        assertEquals("1h 0m 1s", (1.hours + 1.seconds).toString())
        assertEquals("100ms", 100.milliseconds.toString())
    }

    @Test
    fun durationArithmetic() {
        assertEquals(6000L, (3.seconds * 2).inWholeMilliseconds)
        assertEquals(15.minutes, 1.hours / 4)
        assertTrue(5.seconds > 3.seconds)
        assertEquals(1.5, 3.seconds / 2.seconds)
        assertEquals(3600L, 1.hours.inWholeSeconds)
        assertEquals(0.5, 30.seconds.toDouble(DurationUnit.MINUTES))
        assertEquals("1500.00ms", 1.5.seconds.toString(DurationUnit.MILLISECONDS, 2))
        assertEquals(2.seconds, 2000.milliseconds)
        assertEquals(Duration.ZERO, 5.seconds - 5.seconds)
        assertTrue((-1).seconds.isNegative())
        assertEquals(1.seconds, (-1).seconds.absoluteValue)
        val t = measureTime { var x = 0; for (i in 0..1000) x += i }
        assertTrue(t >= Duration.ZERO)
        val tv = measureTimedValue { 6 * 7 }
        assertEquals(42, tv.value)
    }

    @Test
    fun mathFunctions() {
        assertEquals(3, 2.5.roundToInt())
        assertEquals(-2, (-2.5).roundToInt())
        assertEquals(2.0, round(2.5))
        assertEquals(1, 7.mod(3))
        assertEquals(2, (-7).mod(3))
        assertEquals(3, 7.floorDiv(2))
        assertEquals(-4, (-7).floorDiv(2))
        assertEquals(2, 5.countOneBits() )
        assertEquals(4, 5.takeHighestOneBit())
        assertEquals(1.0, sign(-2.0) + 2.0)
        assertEquals(5, (-5).absoluteValue)
        assertEquals(5.0, hypot(3.0, 4.0))
        assertEquals(8.0, 2.0.pow(3))
        assertEquals(0, 8.countTrailingZeroBits() - 3)
        assertEquals(28, 8.countLeadingZeroBits())
        assertEquals(3L, 10L.mod(7L))
    }

    @Test
    fun checksAndLazy() {
        assertFailsWith<IllegalArgumentException> { require(false) { "x" } }
        assertFailsWith<IllegalStateException> { check(false) { "x" } }
        assertFailsWith<IllegalStateException> { error("boom") }
        assertFailsWith<IllegalArgumentException> { requireNotNull<Int>(null) }
        var calls = 0
        val v by lazy { calls++; 5 }
        assertEquals(10, v + v)
        assertEquals(1, calls)
        assertEquals(listOf(1, 2), buildList { add(1); add(2) })
        assertEquals(mapOf(1 to 2), buildMap { put(1, 2) })
        assertEquals(listOf(1, 2, 3), Pair(1, 2).toList() + 3)
        assertEquals("a", "a".also { })
    }

    @Test
    fun kotlinTestApi() {
        assertEquals(1.0, 1.05, 0.1)
        assertFailsWith<AssertionError> { assertEquals(1.0, 1.5, 0.1) }
        assertContentEquals(listOf(1, 2), listOf(1, 2))
        assertContentEquals(arrayOf(1, 2), arrayOf(1, 2))
        assertFailsWith<AssertionError> { assertContentEquals(intArrayOf(1, 2), intArrayOf(1, 3)) }
        assertTrue { true }
        assertFalse { false }
        assertNotNull("x") { assertEquals("x", it) }
        assertIs<String>("s")
        assertIsNot<Int>("s")
        assertContains(listOf(1, 2), 2)
        assertContains("hello", "ell")
        assertContains(1..5, 3)
        assertContains(mapOf(1 to 2), 1)
        assertFails { error("x") }
        assertNotSame("a", "b")
        expect(3) { 1 + 2 }
        val e = assertFailsWith<IllegalStateException> { error("boom") }
        assertEquals("boom", e.message)
    }

    @Test
    fun useAndAutoCloseable() {
        val log = ArrayList<String>()
        class R(val n: String) : AutoCloseable {
            override fun close() { log.add("close " + n) }
        }
        val r = R("a").use { log.add("use"); 5 }
        assertEquals(5, r)
        assertEquals(listOf("use", "close a"), log)
    }
}
