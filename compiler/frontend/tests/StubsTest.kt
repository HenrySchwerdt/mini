package mini.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StubsTest {
    private val src = """package p

class Box(val v: Int) {
    var n: Int = compute(v)
    init {
        println("init")
    }
    constructor() : this(0) {
        println("secondary")
    }
    fun twice(): Int {
        val a = v
        return a + a
    }
    fun inferred() = v + 1
    inline fun <T> use(f: (Int) -> T): T = f(v)
    val big: Boolean
        get() = v > 10
}

const val LIMIT = 10 * 2
val untyped = compute(3)
val typed: Int = compute(4)
fun compute(x: Int): Int { return x * 2 }
fun main() { println("hi") }
"""

    @Test
    fun linesAndColumnsArePreserved() {
        val s = StubWriter.stub(src)
        val a = src.split("\n")
        val b = s.split("\n")
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            val t = a[i].trim()
            // declaration lines and the closing brace of a blanked body stay exactly where they were; the lines inside a body are empty
            if (t.startsWith("fun ") || t.startsWith("class ") || t.startsWith("const ") || t == "}" || t == "init {") {
                assertEquals(a[i].length, b[i].length, "line " + (i + 1) + ": " + b[i])
            }
        }
        assertTrue(b[11 - 1].contains("{") && b[14 - 1].trim() == "}", "the body of twice() keeps its first and last line")
    }

    @Test
    fun bodiesAreBlankedExceptWhatTheConsumerNeeds() {
        val s = StubWriter.stub(src)
        assertFalse(s.contains("println"))
        assertFalse(s.contains("a + a"))
        assertFalse(s.contains("compute(v)"))
        assertFalse(s.contains("compute(4)"))
        assertFalse(s.contains("v > 10"))
        assertTrue(s.contains("fun inferred() = v + 1"), "an expression body without a declared type is kept")
        assertTrue(s.contains("f(v)"), "inline bodies are kept")
        assertTrue(s.contains("10 * 2"), "const initializers are kept")
        assertTrue(s.contains("compute(3)"), "untyped initializers are kept")
        assertTrue(s.contains("fun twice(): Int {"), "signatures are kept")
    }

    @Test
    fun theStubParses() {
        val s = StubWriter.stub(src)
        assertTrue(parseKotlin(s).errors.isEmpty())
    }

    @Test
    fun textWithSyntaxErrorsIsLeftAlone() {
        val bad = "fun f( {"
        assertEquals(bad, StubWriter.stub(bad))
    }
}
