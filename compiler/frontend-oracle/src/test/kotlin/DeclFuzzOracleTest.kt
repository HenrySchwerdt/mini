import mini.frontend.*
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Randomly generated declarations (modifiers, type parameters with variance/bounds/where clauses, receivers, parameters,
 * every kind of type, supertypes, constructors, accessors, enum entries, nesting) compared with the K2 PSI parser, like
 * ExprFuzzOracleTest does for expressions.
 */
class DeclFuzzOracleTest {
    private class Gen(seed: Long) {
        val r = Random(seed)
        fun pick(vararg s: String): String = s[r.nextInt(s.size)]
        fun chance(n: Int): Boolean = r.nextInt(n) == 0
        fun ws(): String = pick(" ", " ", " ", "  ", "\n", "\n    ")

        fun name(): String = pick("a", "b", "foo", "bar", "x1", "T", "Item", "`odd name`")

        fun type(d: Int): String {
            if (d <= 0) return pick("Int", "String", "T", "A.B", "kotlin.Any", "Unit")
            return when (r.nextInt(16)) {
                0, 1, 2 -> pick("List", "Map", "A.B", "kotlin.collections.List", "Array") + "<" + typeArgs(d - 1) + ">"
                3 -> type(d - 1) + "?"
                4 -> "(" + plain(type(d - 1)) + ")" + pick("", "?")
                5 -> "(" + list(0, 3) { type(d - 1) } + ") -> " + type(d - 1)
                6 -> plain(type(d - 1)).let { if (it.contains("->")) "($it)" else it } + ".(" + list(0, 2) { type(d - 1) } + ") -> " + type(d - 1)
                7 -> "(" + list(1, 2) { name() + ": " + type(d - 1) } + ") -> " + type(d - 1)
                8 -> "suspend " + pick("() -> Unit", "(Int) -> String", "Int.() -> Unit")
                9 -> "@Ann " + plain(type(d - 1)).let { if (it.contains("->")) "($it)" else it }
                10 -> "context(" + list(1, 2) { type(d - 1) } + ") () -> Unit"
                11 -> "T & Any"
                12 -> "(" + plain(type(d - 1)) + "?)" + pick("", "?")
                13 -> "@Ann(1) " + pick("Int", "List<String>")
                else -> pick("Int", "String", "T", "A.B<C>")
            }
        }

        /** PSI keeps modifiers inside parentheses in odd places; keep the generated parenthesised types free of them */
        fun plain(t: String): String = if (t.startsWith("suspend") || t.startsWith("@") || t.startsWith("context") || t.startsWith("(")) "Int" else t

        fun typeArgs(d: Int): String = list(1, 3) {
            when (r.nextInt(6)) {
                0 -> "*"
                1 -> "in " + type(d)
                2 -> "out " + type(d)
                else -> type(d)
            }
        }

        fun list(min: Int, max: Int, f: () -> String): String {
            val n = min + r.nextInt(max - min + 1)
            return (0 until n).joinToString(", ") { f() }
        }

        fun typeParams(): String {
            if (chance(2)) return ""
            return "<" + list(1, 3) {
                val mods = pick("", "", "out ", "in ", "reified ", "@Ann ", "@Ann out ")
                mods + pick("T", "U", "V", "K") + (if (chance(3)) " : " + type(1) else "")
            } + ">"
        }

        fun where(): String = if (chance(6)) pick(" where T : Any", " where T : A, T : B", " where U : Comparable<U>") else ""

        fun mods(kind: String): String {
            val all = when (kind) {
                "fun" -> listOf("public", "private", "internal", "protected", "open", "final", "abstract", "override", "inline", "operator", "infix", "tailrec", "suspend", "external", "expect", "actual", "@Ann", "@Ann(1)", "@get:Ann")
                "class" -> listOf("public", "private", "internal", "open", "abstract", "sealed", "data", "inner", "enum", "annotation", "value", "expect", "actual", "@Ann", "fun")
                else -> listOf("public", "private", "internal", "protected", "open", "final", "abstract", "override", "const", "lateinit", "expect", "actual", "@Ann", "@field:Ann", "@Ann(1)")
            }
            val n = r.nextInt(4)
            return (0 until n).joinToString("") { all[r.nextInt(all.size)] + pick(" ", " ", "\n") }
        }

        fun params(d: Int): String = list(0, 3) {
            pick("", "", "vararg ", "noinline ", "crossinline ", "@Ann ") + name() + ": " + type(d) + (if (chance(4)) " = " + pick("1", "null", "{ }", "\"s\"", "foo()") else "")
        }.let { if (chance(8) && it.isNotEmpty()) "$it," else it }

        fun receiver(): String = if (chance(3)) pick("Int", "A.B", "List<T>", "Int?", "(Int) -> Int", "Array<out T>").let { if (it.contains("->")) "($it)." else "$it." } else ""

        fun function(): String =
            mods("fun") + "fun" + ws() + typeParams().let { if (it.isEmpty()) "" else "$it " } + receiver() + name().filter { it != '`' || true } + "(" + params(2) + ")" +
                (if (chance(2)) ": " + type(2) else "") + where() + pick(" {}", " { return 1 }", " = 1", " = foo { 1 }", "", " { }\n")

        fun property(): String {
            val acc = when (r.nextInt(6)) {
                0 -> "\n    get() = 1"
                1 -> "\n    get() { return 1 }\n    set(v) { }"
                2 -> "\n    private set"
                3 -> "\n    @Ann get\n    private set(value) { field = value }"
                else -> ""
            }
            val init = when (r.nextInt(5)) {
                0 -> " = 1"
                1 -> " by lazy { 1 }"
                2 -> " = foo(1, 2)"
                else -> ""
            }
            return mods("prop") + pick("val", "var") + ws() + typeParams().let { if (it.isEmpty()) "" else "$it " } + receiver() + name() +
                (if (chance(2)) ": " + type(2) else "") + where() + init + acc
        }

        fun st(): String = pick("A", "A.B", "List<String>", "Comparable<in T>", "kotlin.Any", "Map<String, List<Int>?>")

        fun superTypes(): String {
            if (chance(2)) return ""
            return " : " + list(1, 3) {
                when (r.nextInt(4)) {
                    0 -> st() + "(" + pick("", "1", "a, b") + ")"
                    1 -> st() + " by " + pick("x", "foo()", "a.b")
                    else -> st()
                }
            }
        }

        fun members(d: Int): String = (0 until r.nextInt(4)).joinToString("\n") { "    " + member(d) }

        fun member(d: Int): String = when (r.nextInt(9)) {
            0, 1 -> function()
            2, 3 -> property()
            4 -> if (d > 0) classDecl(d - 1) else function()
            5 -> pick("init { }", "init { foo() }", "constructor(x: Int) : this()", "constructor() { }", "private constructor(x: String) : super(x)")
            6 -> "companion object" + pick("", " Named") + superTypes() + " { " + (if (d > 0) member(d - 1) else "") + " }"
            7 -> "typealias " + pick("A", "B") + typeParams() + " = " + type(2)
            else -> function()
        }

        fun classDecl(d: Int): String {
            val kind = pick("class", "class", "class", "interface", "object", "enum class", "annotation class", "data class", "sealed class", "sealed interface", "value class", "fun interface")
            val isObject = kind == "object"
            val ctor = if (isObject || kind == "interface" || kind == "fun interface") "" else when (r.nextInt(4)) {
                0 -> "(" + list(0, 3) { pick("val ", "var ", "private val ", "@Ann val ", "") + name() + ": " + type(1) + (if (chance(5)) " = 1" else "") } + ")"
                1 -> pick(" private constructor(x: Int)", " @Ann constructor()", " internal constructor(val x: Int)")
                else -> ""
            }
            val tps = if (isObject) "" else typeParams()
            val entries = if (kind == "enum class") (0 until 1 + r.nextInt(3)).joinToString(",\n    ") { pick("A", "B", "C(1)", "D { }", "E(2) { val x = 1 }", "@Ann F") } + pick(";", "", ",;") + "\n" else ""
            val body = if (chance(4)) "" else " {\n$entries${members(d)}\n}"
            val bodyFix = if (kind == "enum class" && body.isEmpty()) "" else body
            return mods("class") + kind + " " + name().replace("`odd name`", "Odd") + tps + ctor + superTypes() + where() + bodyFix
        }

        fun file(): String {
            val sb = StringBuilder()
            if (chance(4)) sb.append("package a.b\n\n")
            if (chance(4)) sb.append("import x.y.Z\nimport x.y.*\nimport a.b as c\n\n")
            for (i in 0 until 1 + r.nextInt(3)) {
                sb.append(
                    when (r.nextInt(4)) {
                        0 -> function()
                        1 -> property()
                        else -> classDecl(2)
                    },
                ).append("\n\n")
            }
            return sb.toString()
        }
    }

    @Test
    fun randomDeclarationsMatchK2() {
        val failures = ArrayList<String>()
        var compared = 0
        var invalid = 0
        var decls = 0L
        var types = 0L
        val total = 20000
        for (i in 0 until total) {
            val src = Gen(500L + i).file()
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
            decls += o.decls
            types += o.types
            if (ours.errors.isNotEmpty()) {
                failures.add("seed ${500 + i}: our parser reports ${ours.errors.first()}\n$src")
            } else if (a != b && b.any { Regex("""object \w+ ctor""").containsMatchIn(it) }) {
                // PSI reads a `constructor(...)` after a body-less object as the object's (invalid) primary constructor
                invalid++
            } else if (a != b) {
                var k = 0
                while (k < minOf(a.size, b.size) && a[k] == b[k]) k++
                failures.add("seed ${500 + i}:\n$src\n  ours: ${a.getOrElse(k) { "<missing>" }}\n  psi:  ${b.getOrElse(k) { "<missing>" }}")
            }
            if (failures.size >= 5) break
        }
        println("DECL FUZZ: $total generated, $compared accepted by PSI and compared ($decls declarations, $types types), $invalid rejected by PSI, ${failures.size} mismatches")
        assertEquals(emptyList(), failures)
        assertTrue(compared > total / 5)
    }
}
