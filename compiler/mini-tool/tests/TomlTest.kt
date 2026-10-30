package mini.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TomlTest {
    private fun err(text: String): TomlError = assertFailsWith<TomlError> { parseToml(text) }

    @Test
    fun parsesScalarsTablesArraysAndInlineTables() {
        val t = parseToml(
            """
            # comment
            top = "x"
            [package]
            name = "hello"   # trailing
            version = '0.1.0'
            n = 42
            ok = true
            list = [
              "a",
              "b", # c
            ]
            dep = { path = "../x", n = 1 }
            [a.b]
            "quoted key" = "v"
            c.d = 1
            """.trimIndent(),
        )
        assertEquals("x", (t["top"] as TStr).value)
        val p = t["package"] as TTable
        assertEquals("hello", (p["name"] as TStr).value)
        assertEquals("0.1.0", (p["version"] as TStr).value)
        assertEquals(42L, (p["n"] as TInt).value)
        assertEquals(true, (p["ok"] as TBool).value)
        assertEquals(listOf("a", "b"), (p["list"] as TArray).items.map { (it as TStr).value })
        assertEquals("../x", ((p["dep"] as TTable)["path"] as TStr).value)
        val ab = (t["a"] as TTable)["b"] as TTable
        assertEquals("v", (ab["quoted key"] as TStr).value)
        assertEquals(1L, (((ab["c"]) as TTable)["d"] as TInt).value)
    }

    @Test
    fun stringEscapes() {
        val t = parseToml("""s = "a\tb\n\"q\" \\ end"""")
        assertEquals("a\tb\n\"q\" \\ end", (t["s"] as TStr).value)
    }

    @Test
    fun positionsAreExact() {
        val t = parseToml("[package]\n  name = \"hello\"\n")
        val e = ((t["package"] as TTable).entries["name"])!!
        assertEquals(2, e.keySpan.line); assertEquals(3, e.keySpan.col); assertEquals(4, e.keySpan.len)
        assertEquals(2, e.value.span.line); assertEquals(10, e.value.span.col); assertEquals(7, e.value.span.len)
    }

    @Test
    fun missingEquals() {
        val e = err("[package]\nname \"hello\"\n")
        assertTrue(e.msg.contains("expected `=`"), e.msg)
        assertEquals(2, e.span.line)
        assertNotNull(e.hint)
    }

    @Test
    fun unquotedValueSuggestsQuotes() {
        val e = err("name = hello\n")
        assertEquals("unquoted value `hello`", e.msg)
        assertEquals(1, e.span.line); assertEquals(8, e.span.col); assertEquals(5, e.span.len)
        assertEquals("strings need quotes: write \"hello\"", e.hint)
    }

    @Test
    fun unterminatedString() {
        val e = err("a = 1\nname = \"hello\n")
        assertEquals("unterminated string", e.msg)
        assertEquals(2, e.span.line); assertEquals(8, e.span.col)
    }

    @Test
    fun duplicateKeyPointsAtBoth() {
        val e = err("[p]\nname = \"a\"\nname = \"b\"\n")
        assertEquals("duplicate key `name`", e.msg)
        assertEquals(3, e.span.line)
        assertEquals(2, e.related!!.first.line)
    }

    @Test
    fun duplicateTable() {
        val e = err("[a]\nx = 1\n[a]\n")
        assertTrue(e.msg.contains("`[a]` is defined more than once"))
        assertEquals(3, e.span.line)
    }

    @Test
    fun unsupportedFeaturesGetFriendlyErrors() {
        assertTrue(err("[[x]]\n").msg.contains("arrays of tables"))
        assertTrue(err("a = \"\"\"x\"\"\"\n").msg.contains("multi-line strings"))
        assertTrue(err("a = 1.5\n").msg.contains("floats"))
        assertTrue(err("a = \"x\\q\"\n").msg.contains("unknown escape"))
    }

    @Test
    fun trailingGarbageAfterValue() {
        val e = err("a = 1 2\n")
        assertTrue(e.msg.contains("unexpected"))
        assertEquals(7, e.span.col)
    }

    @Test
    fun unterminatedArrayAndInlineTable() {
        assertEquals("unterminated array", err("a = [1, 2\n").msg)
        assertEquals("unterminated inline table", err("a = { b = 1\n").msg)
    }

    @Test
    fun rendersLikeRustc() {
        val src = "[package]\nname = hello\n"
        val e = err(src)
        val out = renderSourceError(e, "mini.toml", src, Style.Plain)
        val expected = """
            error: unquoted value `hello`
             --> mini.toml:2:8
              |
            2 | name = hello
              |        ^^^^^ not a valid value
              = help: strings need quotes: write "hello"
        """.trimIndent() + "\n"
        assertEquals(expected, out)
    }

    @Test
    fun renderWithRelatedLocation() {
        val src = "[p]\nname = \"a\"\nname = \"b\"\n"
        val out = renderSourceError(err(src), "mini.toml", src, Style.Plain)
        assertTrue(out.contains("2 | name = \"a\""), out)
        assertTrue(out.contains("3 | name = \"b\""), out)
        assertTrue(out.contains("first defined here"), out)
    }
}
