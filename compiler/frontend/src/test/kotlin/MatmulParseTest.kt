import mini.frontend.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mini's matrix operator `@`: binary only after a complete operand with whitespace on both sides (`@=` for the compound
 * assignment); `@Ident` is an annotation and `ident@` a label, everywhere.
 */
class MatmulParseTest {
    private fun shape(e: Expr?): String = when (e) {
        null -> "-"
        is BinaryExpr -> "(" + e.op + " " + shape(e.left) + " " + shape(e.right) + ")"
        is NameExpr -> e.name
        is LiteralExpr -> e.text
        is ParenExpr -> "[" + shape(e.inner) + "]"
        is AnnotatedExpr -> "(@ " + e.annotations.size + " " + shape(e.expr) + ")"
        is LabeledExpr -> "(label " + e.label + " " + shape(e.expr) + ")"
        is ReturnExpr -> "(return " + e.label + " " + shape(e.expr) + ")"
        is ThisExpr -> "this@" + e.label
        is CallExpr -> "call " + shape(e.callee)
        else -> e::class.simpleName!!
    }

    private fun expr(src: String): String {
        val r = parseKotlin("val x = $src\n")
        assertEquals(emptyList(), r.errors.map { it.message }, src)
        assertEquals("val x = $src\n", printCst(r.root, r.tokens, r.text), "lossless: $src")
        return shape((r.file.declarations.single() as PropertyDecl).initializer)
    }

    private fun errors(src: String): List<String> = parseKotlin(src).errors.map { it.message }

    @Test
    fun binaryWithWhitespaceOnBothSides() {
        assertEquals("(@ a b)", expr("a @ b"))
        assertEquals("(@ a b)", expr("a  @   b"))
        assertEquals("(@ [(@ a b)] c)", expr("(a @ b) @ c"))
        assertEquals("(@ a (@ b c))", expr("a @ (b @ c)").replace("[", "").replace("]", ""))
    }

    @Test
    fun sameLevelAsMultiplicativeAndLeftAssociative() {
        assertEquals("(@ (@ a b) c)", expr("a @ b @ c"))
        assertEquals("(+ a (@ b c))", expr("a + b @ c"))
        assertEquals("(+ (@ a b) c)", expr("a @ b + c"))
        assertEquals("(* (@ a b) c)", expr("a @ b * c"))
        assertEquals("(@ (* a b) c)", expr("a * b @ c"))
        assertEquals("(% (/ (@ a b) c) d)", expr("a @ b / c % d"))
        assertEquals("(== (@ a b) c)", expr("a @ b == c"))
        assertEquals("(@ a b.c)".replace("b.c", "DotExpr"), expr("a @ b.c"))
        assertEquals("(@ a call f)", expr("a @ f(1)"))
    }

    @Test
    fun compoundAssignment() {
        val r = parseKotlin("fun f() {\n    a @= b\n    a @= b @ c\n}\n")
        assertEquals(emptyList(), r.errors.map { it.message })
        val body = ((r.file.declarations.single() as FunDecl).body as BlockExpr).stmts
        assertEquals(2, body.size)
        assertEquals("(@= a b)", shape(body[0] as Expr))
        assertEquals("(@= a (@ b c))", shape(body[1] as Expr))
        assertEquals("fun f() {\n    a @= b\n    a @= b @ c\n}\n", printCst(r.root, r.tokens, r.text))
    }

    @Test
    fun annotationWithoutWhitespaceStaysAnAnnotation() {
        // annotated expressions
        assertEquals("(@ 1 a)", expr("@Ann a"))
        assertEquals("(+ a (@ 1 b))", expr("a + @Ann b"))
        // a statement starting with an annotation after another statement
        val r = parseKotlin("fun f() {\n    g()\n    @Suppress(\"x\") h()\n}\n")
        assertEquals(emptyList(), r.errors.map { it.message })
        val body = ((r.file.declarations.single() as FunDecl).body as BlockExpr).stmts
        assertEquals(2, body.size)
    }

    @Test
    fun labelsAndThisLabelsAreUnaffected() {
        val src = """
            class Outer {
                inner class In {
                    fun f(xs: List<Int>): Int {
                        val o = this@Outer
                        val i = this@In
                        loop@ for (x in xs) {
                            if (x == 1) continue@loop
                            if (x == 2) break@loop
                        }
                        xs.forEach lit@{
                            if (it == 3) return@lit
                        }
                        return@f 1
                    }
                }
            }
        """.trimIndent() + "\n"
        val r = parseKotlin(src)
        assertEquals(emptyList(), r.errors.map { it.message })
        assertEquals(src, printCst(r.root, r.tokens, r.text))
    }

    @Test
    fun useSiteTargetsAndFileAnnotationsAreUnaffected() {
        val src = """
            @file:Suppress("unused")

            package p

            class C(@get:Ann val a: Int, @param:Ann var b: Int) {
                @field:Ann
                var c: Int = 0
                @set:Ann
                var d: Int = 0
            }

            @Ann
            fun f(@Ann x: Int): Int = x
        """.trimIndent() + "\n"
        val r = parseKotlin(src)
        assertEquals(emptyList(), r.errors.map { it.message })
        assertEquals(src, printCst(r.root, r.tokens, r.text))
    }

    @Test
    fun noWhitespaceAfterAtIsNeverBinary() {
        // `a @b` is `a` followed by an annotation, never a matrix product (K2 accepts `expr @Ann class C` on one line,
        // so the parser must not claim `@` here; in an expression position it is simply a syntax error)
        assertTrue(errors("val x = a @b\n").isNotEmpty())
        assertTrue(errors("val x = f(1)@b\n").isNotEmpty())
        assertTrue(errors("fun f() { a @ = b }\n").isNotEmpty())
        assertTrue(errors("fun f() { a@=b }\n").isNotEmpty())
        assertEquals(emptyList(), errors("fun f(): Int = 1 @Suppress(\"x\")\nclass C\n"))
    }

    @Test
    fun noWhitespaceBeforeAtIsNeverBinary() {
        // `a@ b` is a label on `b` (as in Kotlin), not a matrix product
        assertEquals("(label a b)", expr("a@ b"))
    }

    @Test
    fun atAtTheStartOfAStatementAfterANewlineStaysAnAnnotation() {
        val r = parseKotlin("fun f() {\n    val a = b\n    @Ann val c = 1\n}\n")
        assertEquals(emptyList(), r.errors.map { it.message })
    }
}
