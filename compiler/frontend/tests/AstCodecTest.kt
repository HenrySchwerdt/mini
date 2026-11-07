import mini.frontend.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mini.io.readTextFile

/** The encoding of parsed files behind the stub cache (AstCache.kt): decoding gives back what was encoded. */
class AstCodecTest {
    private val src = """@file:Suppress("x")
package p.q

import a.b.C
import a.b.*
import a.b.D as E

annotation class Ann(val v: Int)

@Ann(1) sealed interface S<out T : Any> where T : Comparable<T> { fun <R> map(f: (T) -> R): R }
data class P(val x: Int, var y: String = "a${'$'}{1 + 2}b", private val z: List<Map<String, Int?>>? = null) : S<Int>, Cloneable by impl() {
    companion object { const val K = 1L; val u = 2.5f }
    constructor(a: Int) : this(a, "b") { println(a) }
    init { check(true) }
    val big: Boolean get() = x > 10
    var w: Int = 0
        get() = field + 1
        private set(v) { field = v }
    val lazyV by lazy { 3 }
    operator fun plus(o: P): P = P(x + o.x, y + o.y)
    inline fun <reified T> use(noinline f: T.(Int) -> Unit, crossinline g: suspend () -> Int): Int = g.hashCode()
}
enum class Color(val rgb: Int) { RED(1), GREEN(2) { override fun toString() = "g" }; fun f() = rgb }
typealias L<T> = List<T>
object O : Any() { val a = arrayOf(1, 2, 3); }
fun String.ext(vararg xs: Int, d: Int = 3): Int {
    val (a, b) = xs.size to 2
    var i = 0
    loop@ while (i < 10) { i++; if (i == 5) continue@loop else if (i == 7) break@loop }
    do { i-- } while (i > 0)
    for ((k, v) in mapOf(1 to 2)) { println("${'$'}k=${'$'}v ${'$'}{k + v}") }
    for (c in 0..3 step 2) {}
    val r = when (i) { 0, 1 -> "a"; in 2..4 -> "b"; is Int -> "c"; else -> "d" }
    val t = try { 1 / 0 } catch (e: Exception) { -1 } finally { println() }
    val l = { x: Int, (y, z): Pair<Int, Int> -> x + y + z }
    val o = object : Runnable { override fun run() {} }
    val fr = ::println
    val fr2 = String::length
    val ch = 'c'; val esc = "\nA"
    val n = a?.b?.c ?: throw IllegalStateException("x")
    val s = (a as? Int)!! + (b as Int) - (-1) + (c is String).hashCode()
    return xs[0].also { it + 1 }.let { x -> x }
}
"""

    private fun roundTrip(text: String): Boolean {
        val r = parseKotlinUnquoted(text)
        if (r.errors.isNotEmpty()) return false
        val enc = AstCodec.encode(listOf(r.file))
        val back = AstCodec.decode(enc, 0)!!
        assertEquals(1, back.size)
        assertEquals(enc, AstCodec.encode(back))
        assertEquals(r.file.declarations.size, back[0].declarations.size)
        assertEquals(r.file.packageName, back[0].packageName)
        assertEquals(r.file.end, back[0].end)
        return true
    }

    @Test
    fun roundTripOfAFileWithMostConstructs() {
        assertTrue(roundTrip(src), "the sample parses")
    }

    @Test
    fun roundTripOfTheStandardLibraryAndTheFrontend() {
        var n = 0
        for (f in Corpus.files) {
            val rel = Corpus.rel(f)
            if (!(rel.startsWith("stdlib/") || rel.startsWith("compiler/frontend/src/"))) continue
            if (roundTrip(readTextFile(f))) n++
        }
        assertTrue(n > 100, "many files were round-tripped: " + n)
    }

    @Test
    fun severalFilesAndAPrefix() {
        val a = parseKotlinUnquoted(src).file
        val b = parseKotlinUnquoted("package z\nfun f() = 1\n").file
        val enc = "prefix\n" + AstCodec.encode(listOf(a, b))
        val back = AstCodec.decode(enc, 7)!!
        assertEquals(2, back.size)
        assertEquals("z", back[1].packageName)
    }

    @Test
    fun damagedInputIsRejected() {
        val enc = AstCodec.encode(listOf(parseKotlinUnquoted(src).file))
        assertNull(AstCodec.decode(enc.substring(0, enc.length - 1), 0), "no end marker")
        assertNull(AstCodec.decode(enc.substring(0, enc.length / 2), 0), "truncated")
        assertNull(AstCodec.decode("", 0))
        assertNull(AstCodec.decode(enc + "x", 0), "trailing data")
    }
}
