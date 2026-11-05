import kotlin.test.*

class StringsTest {
    @Test
    fun split() {
        assertEquals(listOf("a", "b", "", "c"), "a,b,,c".split(","))
        assertEquals(listOf("a", "b,c"), "a,b,c".split(",", limit = 2))
        assertEquals(listOf("", "a", "b", "c", ""), "abc".split(""))
        assertEquals(listOf("a", "b", "c"), "a-b_c".split('-', '_'))
        assertEquals(listOf("a", "b", "c"), "a1b2c".split("1", "2"))
        assertEquals(listOf("A", "C"), "AbC".split("b", ignoreCase = true))
        assertEquals(listOf(""), "".split(","))
        assertEquals(listOf("a", "b"), "a b".splitToSequence(" ").toList())
    }

    @Test
    fun linesAndMargins() {
        assertEquals(listOf("a", "b", "c"), "a\nb\r\nc".lines())
        assertEquals(listOf("a", "b"), "a\nb".lineSequence().toList())
        assertEquals("first\n  second", "\n        |first\n        |  second\n    ".trimMargin())
        assertEquals("a\nb", "\n    a\n    b\n".trimIndent())
        assertEquals("> abc\n> def", "abc\ndef".prependIndent("> "))
    }

    @Test
    fun replaceFamily() {
        assertEquals("a+b-c", "a-b-c".replaceFirst("-", "+"))
        assertEquals("He__o", "Hello".replace("L", "_", ignoreCase = true))
        assertEquals("zbc", "abc".replaceRange(0, 1, "z"))
        assertEquals("Jllo", "hello".replaceBefore("l", "J"))
        assertEquals("helJ", "hello".replaceAfter("l", "J"))
        assertEquals("af", "abcdef".removeRange(1..4))
        assertEquals("bcd", "abcdef".substring(1..3))
        assertEquals("ab", "abcdef".slice(0..1))
        assertEquals("Kotlin", "kotlin".replaceFirstChar { it.uppercaseChar() })
        assertEquals("Kotlin", "kotlin".capitalize())
    }

    @Test
    fun searching() {
        assertTrue("abc".startsWith("AB", ignoreCase = true))
        assertTrue("abc".endsWith("BC", ignoreCase = true))
        assertTrue("abc".contains("BC", true))
        assertEquals(2, "abc".indexOf("c", 0, false))
        assertEquals("ello", "hello".commonSuffixWith("jello"))
        assertEquals(-1, "abc".compareTo("ABD", ignoreCase = true))
        assertEquals(1, "abc".indexOfAny(charArrayOf('x', 'b')))
    }

    @Test
    fun windowedZipChunked() {
        assertEquals(listOf("ab", "bc"), "abc".windowed(2))
        assertEquals(listOf("ab", "cd", "e"), "abcde".windowed(2, 2, true))
        assertEquals(listOf("abc", "de"), "abcde".chunked(3))
        assertEquals(listOf(Pair('a', 'x'), Pair('b', 'y')), "ab".zip("xyz"))
        assertEquals(listOf(Pair('a', 'b'), Pair('b', 'c')), "abc".zipWithNext())
    }

    @Test
    fun charFunctions() {
        assertEquals(7, '7'.digitToInt())
        assertNull('x'.digitToIntOrNull())
        assertEquals(11, 'b'.digitToInt(16))
        assertEquals('B', 11.digitToChar(16))
        assertEquals("abc", "hello".filter { it in "abc" } + "abc")
        assertEquals("heo", "hello".asSequence().filter { it != 'l' }.joinToString(""))
        assertEquals(mapOf('l' to 2), "hello".groupBy { it }.mapValues { it.value.size }.filter { it.value > 1 })
        assertEquals(Pair("a", "bc"), "abc".partition { it == 'a' })
        assertEquals('z', "zebra".maxOrNull())
        assertEquals(setOf('a', 'b'), "abab".toSet())
    }

    @Test
    fun numbers() {
        assertEquals(3.5f, "3.5".toFloat())
        assertEquals(255, "ff".toInt(16))
        assertNull("x".toIntOrNull())
        assertEquals(-12L, "-12".toLong())
        assertEquals(2.5, "2.5".toDouble())
        assertNull("1e".toDoubleOrNull())
    }

    @Test
    fun stringBuilderApi() {
        val sb = StringBuilder()
        sb.append(1.5f).append("xyz", 0, 1).insert(0, 5).replace(0, 1, "zz")
        assertEquals("zz1.5x", sb.toString())
        assertEquals(5, sb.indexOf("x"))
        sb.deleteCharAt(0)
        assertEquals("z1.5x", sb.toString())
        sb.setCharAt(0, 'q')
        assertEquals("q1.5x", sb.toString())
        sb.appendLine(2L).appendLine(true)
        assertEquals("q1.5x2\ntrue\n", sb.toString())
        sb.insert(0, 3.5).insert(0, false)
        assertEquals("fal", sb.substring(0..2))
        assertEquals("ab1\n2.0", buildString { append("a"); append('b'); append(1); appendLine(); append(2.0) })
        assertTrue(StringBuilder("abc").contentEquals("abc"))
        assertEquals("cba", StringBuilder("abc").reverse().toString())
        assertEquals(16, StringBuilder().capacity())
    }
}
