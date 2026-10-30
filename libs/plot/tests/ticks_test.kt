import kotlin.test.*
import mini.plot.*

class TicksTest {
    @Test
    fun niceNumbersAreOneTwoFiveTen() {
        assertEquals(1.0, niceNumber(0.9, true))
        assertEquals(2.0, niceNumber(2.4, true))
        assertEquals(5.0, niceNumber(4.0, true))
        assertEquals(10.0, niceNumber(8.0, true))
        assertEquals(5.0, niceNumber(3.1, false))
        assertEquals(0.02, niceNumber(0.0123, false))
    }

    @Test
    fun ticksCoverTheRange() {
        val t = niceTicks(0.0, 10.0, 6)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), t.values)
        assertEquals(2.0, t.step)
        assertEquals(0, t.decimals)
    }

    @Test
    fun ticksAreExtendedToNiceBounds() {
        val t = niceTicks(0.13, 9.7, 6)
        assertEquals(0.0, t.first)
        assertEquals(10.0, t.last)
    }

    @Test
    fun fractionalSteps() {
        val t = niceTicks(0.0, 1.0, 6)
        assertEquals(listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), t.values)
        assertEquals(1, t.decimals)
        assertEquals("0.4", tickLabel(0.4, t))
        val u = niceTicks(-0.003, 0.012, 5)
        assertEquals(3, u.decimals)
        assertTrue(u.first <= -0.003)
        assertTrue(u.last >= 0.012)
    }

    @Test
    fun negativeRanges() {
        val t = niceTicks(-1.0, 1.0, 5)
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5, 1.0), t.values)
        assertEquals("-0.5", tickLabel(-0.5, t))
    }

    @Test
    fun degenerateRangesAreWidened() {
        val same = niceTicks(5.0, 5.0, 5)
        assertTrue(same.first < 5.0)
        assertTrue(same.last > 5.0)
        val zero = niceTicks(0.0, 0.0, 5)
        assertTrue(zero.first < 0.0)
        assertTrue(zero.last > 0.0)
        val reversed = niceTicks(10.0, 0.0, 6)
        assertEquals(0.0, reversed.first)
        assertEquals(10.0, reversed.last)
        val nan = niceTicks(Double.NaN, Double.NaN, 5)
        assertTrue(nan.values.size >= 2)
    }

    @Test
    fun hugeAndTinyValuesUseScientificNotation() {
        val big = niceTicks(0.0, 5.0e9, 5)
        assertEquals("2e9", tickLabel(2.0e9, big))
        assertEquals("1.5e7", scientific(15000000.0, 2))
        assertEquals("-2e-5", scientific(-0.00002, 2))
    }

    @Test
    fun fixedFormatting() {
        assertEquals("3.14", fixed(3.14159, 2))
        assertEquals("-0.50", fixed(-0.5, 2))
        assertEquals("1000", fixed(1000.0, 0))
        assertEquals("0.05", fixed(0.05, 2))
        assertEquals("NaN", fixed(Double.NaN, 1))
    }
}
