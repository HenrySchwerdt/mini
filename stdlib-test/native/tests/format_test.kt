import kotlin.test.*

class FormatTest {
    @Test
    fun integers() {
        assertEquals("1 a 2.50", "%d %s %.2f".format(1, "a", 2.5))
        assertEquals("   42|ab   |", "%5d|%-5s|".format(42, "ab"))
        assertEquals("1,234,567", "%,d".format(1234567))
        assertEquals("+5  5 (5)", "%+d % d %(d".format(5, 5, -5))
        assertEquals("00042", "%05d".format(42))
        assertEquals("-0042", "%05d".format(-42))
        assertEquals("ff FF 10 0xff ffffffff", "%x %X %o %#x %x".format(255, 255, 8, 255, -1))
        assertEquals("9223372036854775807", "%d".format(Long.MAX_VALUE))
    }

    @Test
    fun floating() {
        assertEquals("003.1", "%05.1f".format(3.14159))
        assertEquals("-003.142", "%08.3f".format(-3.14159))
        assertEquals("1 2 3", "%.0f %.0f %.0f".format(0.5, 1.5, 2.5))
        assertEquals("0.3", "%.1f".format(0.25))
        assertEquals("2.68", "%.2f".format(2.675))
        assertEquals("1.234568e+04", "%e".format(12345.6789))
        assertEquals("1.235e-04", "%.3e".format(0.000123456))
        assertEquals("0.000123400 1.23457e+08", "%g %g".format(0.0001234, 123456789.0))
        assertEquals("100000000000000000000.000000", "%f".format(1e20))
        assertEquals("0.33", "%.2f".format(1.0 / 3))
        assertEquals(" 45.7%", "%5.1f%%".format(45.678))
        assertEquals("1,234,567.89", "%,.2f".format(1234567.891))
        assertEquals("NaN Infinity -Infinity", "%f %f %f".format(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
        assertEquals("100.00", "%.2f".format(100.0f))
        assertEquals("0.00", "%.2f".format(0.0))
    }

    @Test
    fun othersAndIndexes() {
        assertEquals("null ABC true x %", "%s %S %b %c %%".format(null, "abc", true, 'x'))
        assertEquals("b a", "%2\$s %1\$s".format("a", "b"))
        assertEquals("a a", "%s %<s".format("a"))
        assertEquals("[1, 2]", "%s".format(listOf(1, 2)))
        assertEquals("abc|        hi|hi        |", "%.3s|%10s|%-10s|".format("abcdef", "hi", "hi"))
        assertEquals("7\n", "%d%n".format(7))
        assertEquals("false", "%b".format(null))
    }

    @Test
    fun errors() {
        assertFailsWith<IllegalArgumentException> { "%d".format() }
        assertFailsWith<IllegalArgumentException> { "%q".format(1) }
        assertFailsWith<IllegalArgumentException> { "%d".format("x") }
    }
}
