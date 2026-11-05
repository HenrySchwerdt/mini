import kotlin.test.*

class RegexTest {
    @Test
    fun findAndGroups() {
        val r = Regex("(\\d+)-(\\d+)")
        assertTrue(r.matches("12-34"))
        assertFalse(r.matches("12-34x"))
        val m = r.find("tel 555-1234 x")!!
        assertEquals("555-1234", m.value)
        assertEquals(listOf("555-1234", "555", "1234"), m.groupValues)
        assertEquals(4..11, m.range)
        assertNull(r.find("none"))
        assertEquals(3, m.groups.size)
    }

    @Test
    fun replace() {
        val r = Regex("(\\d+)-(\\d+)")
        assertEquals("2:1 and 4:3", r.replace("1-2 and 3-4", "$2:$1"))
        assertEquals("a<1>b<2>c<3>", "a1b22c333".replace(Regex("\\d+")) { "<" + it.value.length + ">" })
        assertEquals("tHE qUICK", "The Quick".replace(Regex("(\\w)(\\w*)")) { it.groupValues[1].lowercase() + it.groupValues[2].uppercase() })
        assertEquals("ba", "aaa".replace(Regex("a{2}"), "b"))
        assertEquals("a\\b", "a b".replace(Regex("\\s"), "\\\\"))
        assertEquals("-x-", "x".replace(Regex("(?:)"), "-"))
        assertEquals("b-a", Regex("a").replaceFirst("a-a", "b"))
        assertEquals("Hell0 W0rld", "Hello World".replace(Regex("o", RegexOption.IGNORE_CASE), "0"))
    }

    @Test
    fun splitAndAll() {
        assertEquals(listOf("a", "b", "c"), "a, b;c".split(Regex("[,;]\\s*")))
        assertEquals(listOf("2021", "03-04"), "2021-03-04".split(Regex("-"), 2))
        assertEquals(listOf("e", "u", "a", "i", "o"), Regex("[aeiou]").findAll("education").map { it.value }.toList())
        assertEquals(listOf("a.b@c.d", "e@f.g"), Regex("[\\w.]+@[\\w.]+").findAll("a.b@c.d, e@f.g").map { it.value }.toList())
        assertEquals(listOf(0..-1, 1..1, 2..1, 3..2), Regex("x*").findAll("axb").map { it.range }.toList())
    }

    @Test
    fun namedAndDestructured() {
        val d = Regex("(?<y>\\d{4})-(?<mo>\\d{2})").find("on 2024-05-17")!!
        assertEquals("2024", d.groups["y"]?.value)
        assertEquals("05", d.groups["mo"]!!.value)
        val (a, b) = Regex("(\\w+)@(\\w+)\\.com").matchEntire("joe@site.com")!!.destructured
        assertEquals("joe", a)
        assertEquals("site", b)
        assertNull(Regex("(a)|(b)").find("b")!!.groups[1])
        assertEquals(listOf("b", "", "b"), Regex("(a)|(b)").find("b")!!.groupValues)
    }

    @Test
    fun syntax() {
        assertEquals(2, Regex("^abc$", RegexOption.MULTILINE).findAll("abc\nabc").count())
        assertFalse(Regex("a.c").matches("a\nc"))
        assertTrue(Regex("a.c", RegexOption.DOT_MATCHES_ALL).matches("a\nc"))
        assertEquals(listOf("abcd", "a", "bcd", ""), Regex("(a|ab)(c|bcd)(d*)").find("abcd")!!.groupValues)
        assertTrue(Regex("(?i)hello").containsMatchIn("say HELLO"))
        assertEquals(2, Regex("\\bfoo\\b").findAll("foo food foo").count())
        assertEquals("ll", Regex("(\\w)\\1").find("hello")!!.value)
        assertEquals("aaab", Regex("a*?b").find("aaab")!!.value)
        assertEquals("a", Regex("a+?").find("aaa")!!.value)
        assertEquals("ababab", Regex("(ab)+").find("xababab")!!.value)
        assertEquals(listOf("Hello", "World"), Regex("\\p{Upper}\\p{Lower}+").findAll("Hello World").map { it.value }.toList())
        assertTrue(Regex("[a-c&&[^b]]+").matches("acca"))
        assertFalse(Regex("[a-c&&[^b]]+").matches("abc"))
        assertTrue(Regex("\\Qa.b\\E").matches("a.b"))
        assertFalse(Regex("\\Qa.b\\E").matches("axb"))
        assertEquals("aaa", Regex("a++").find("aaab")?.value)
        assertNull(Regex("a++a").find("aaa"))
    }

    @Test
    fun lookaround() {
        assertEquals("there", Regex("\\w+(?=!)").find("hi there!")!!.value)
        assertEquals(listOf("22", "44"), Regex("(?<!\\d)\\d{2}(?!\\d)").findAll("1 22 333 44").map { it.value }.toList())
        assertEquals("42", Regex("(?<=\\$)\\d+").find("cost $42")!!.value)
        assertNull(Regex("a(?!b)").find("ab"))
    }

    @Test
    fun helpers() {
        assertEquals("a\\.b\\*c", Regex.escape("a.b*c"))
        assertEquals("a.b", "a.b".toRegex().pattern)
        assertTrue("abc" matches Regex("[a-c]+"))
        assertTrue("xabcx".contains(Regex("b")))
        assertTrue(Regex.fromLiteral("a.b").matches("a.b"))
        assertFailsWith<IllegalArgumentException> { Regex("(") }
        assertFailsWith<IllegalArgumentException> { Regex("*a") }
    }

    @Test
    fun manyIterations() {
        val big = "ab".repeat(2000)
        assertTrue(Regex("(ab)*").matches(big))
        assertTrue(Regex(".*b").matches(big))
        assertEquals(2000, Regex("ab").findAll(big).count())
    }
}
