import mini.frontend.*
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Randomly generated expressions (all operators, prefix/postfix forms, calls, lambdas, control-flow expressions)
 * rendered without redundant parentheses and with random whitespace and line breaks. Whatever the K2 PSI parser accepts
 * must parse to exactly the same fully parenthesised outline in our parser; this checks precedence, associativity and
 * the newline rules far beyond what the repository sources contain.
 */
class ExprFuzzOracleTest {
    private class Gen(seed: Long) {
        val r = Random(seed)

        fun pick(vararg s: String): String = s[r.nextInt(s.size)]

        fun ws(): String = when (r.nextInt(12)) {
            0 -> "\n"
            1 -> "\n    "
            2 -> "  "
            else -> " "
        }

        fun wsNoNl(): String = if (r.nextInt(8) == 0) "  " else " "

        fun atom(): String = when (r.nextInt(14)) {
            0 -> "a"
            1 -> "b"
            2 -> "c"
            3 -> "x1"
            4 -> "1"
            5 -> "2.5"
            6 -> "\"s\""
            7 -> "\"t\${a}\""
            8 -> "this"
            9 -> "null"
            10 -> "true"
            11 -> "'c'"
            12 -> "0x1F"
            else -> "d"
        }

        fun type(): String = pick("Int", "String", "List<Int>", "Map<String, Int>", "Foo.Bar", "Int?", "(Int) -> Int", "List<*>", "A<B<C>>")

        fun binOp(): String = pick(
            "+", "-", "*", "/", "%", "..", "..<", "?:", "==", "!=", "===", "!==", "<", ">", "<=", ">=", "&&", "||",
            "in", "!in", "to", "shl", "and", "or", "until", "step", "downTo",
        )

        fun expr(d: Int): String {
            if (d <= 0) return atom()
            return when (r.nextInt(30)) {
                in 0..8 -> binary(d)
                9 -> expr(d - 1) + wsNoNl() + pick("is", "!is") + " " + type()
                10 -> expr(d - 1) + wsNoNl() + pick("as", "as?") + " " + type()
                11 -> pick("-", "+", "!", "++", "--") + expr(d - 1)
                12 -> pick("-", "!") + " " + expr(d - 1)
                13 -> post(d)
                14 -> "(" + expr(d - 1) + ")"
                15 -> "if (" + expr(d - 1) + ") " + expr(d - 1) + pick(" else ", "\nelse ", "; else ") + expr(d - 1)
                16 -> "when (" + expr(d - 1) + ") { 1 -> " + expr(d - 1) + "; else -> " + expr(d - 1) + " }"
                17 -> "when { " + expr(d - 1) + " -> " + expr(d - 1) + pick("; ", "\n") + "else -> " + expr(d - 1) + " }"
                18 -> "{ " + pick("", "p -> ", "p, q -> ", "p: Int -> ") + expr(d - 1) + " }"
                19 -> "try { " + expr(d - 1) + " } catch (e: Exception) { " + expr(d - 1) + " }"
                20 -> "listOf(" + expr(d - 1) + pick("", ", " + atom()) + ")"
                21 -> "foo(" + expr(d - 1) + ")" + ws() + "{ " + expr(d - 1) + " }"
                22 -> expr(d - 1) + pick(".", "?.", "!!.") + pick("f", "g()", "h(" + atom() + ")", "k { 1 }")
                23 -> expr(d - 1) + "[" + expr(d - 1) + "]"
                24 -> pick("::foo", "A::bar", "A::class", "a.b::c", "this::d", "a?::e")
                25 -> expr(d - 1) + pick("!!", "++", "--")
                26 -> "object : A() { val x = " + expr(d - 1) + " }"
                27 -> "foo<Int>(" + expr(d - 1) + ")"
                28 -> "fun(x: Int) = " + expr(d - 1)
                else -> binary(d)
            }
        }

        fun binary(d: Int): String {
            val op = binOp()
            // PSI treats a line break before `in` / `!in` specially (as a modifier of what follows): keep those on one line
            val before = if (op == "in" || op == "!in") wsNoNl() else ws()
            return expr(d - 1) + before + op + ws() + expr(d - 1)
        }

        fun post(d: Int): String = expr(d - 1) + pick("()", "(1)", "(1, 2)", "[0]", ".x", "?.x", "!!", ".x()", "++", "--")

        fun statement(): String {
            val e = expr(1 + r.nextInt(4))
            return when (r.nextInt(8)) {
                0 -> "a = $e"
                1 -> "a += $e"
                2 -> "val v = $e"
                3 -> "return $e"
                4 -> e + "\n" + expr(1 + r.nextInt(3))
                5 -> "a.b[c] = $e"
                else -> e
            }
        }
    }

    @Test
    fun randomExpressionsMatchK2() {
        val failures = ArrayList<String>()
        var compared = 0
        var invalid = 0
        var exprs = 0L
        val total = 30000
        for (i in 0 until total) {
            val g = Gen(1000L + i)
            val src = "fun f() {\n" + g.statement() + "\n}\n"
            val psi = PsiOracle.parse("fuzz.kt", src)
            if (PsiOracle.errors(psi).isNotEmpty()) {
                invalid++
                continue
            }
            val ours = parseKotlin(src)
            val o = Outline()
            val a = o.file(ours.file)
            val b = PsiOutline().file(psi)
            compared++
            exprs += o.exprs
            if (ours.errors.isNotEmpty()) {
                failures.add("seed ${1000 + i}: our parser reports ${ours.errors.first()}\n$src")
            } else if (a != b) {
                failures.add("seed ${1000 + i}:\n$src\n  ours: ${a.last()}\n  psi:  ${b.last()}")
            }
            if (failures.size >= 8) break
        }
        println("EXPR FUZZ: $total generated, $compared accepted by PSI and compared ($exprs expressions), $invalid rejected by PSI, ${failures.size} mismatches")
        assertEquals(emptyList(), failures)
        assertTrue(compared > total / 4)
    }
}
