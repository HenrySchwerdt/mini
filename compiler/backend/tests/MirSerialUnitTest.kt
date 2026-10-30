import mini.backend.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MirSerialUnitTest {
    private fun roundTripConstants(consts: List<MirExpr>): List<MirExpr> {
        val body = consts.map { MirStmt.Eval(it) }
        val f = MirFunction("t.f", "kt_t_Df", emptyList(), TUnit, body)
        val back = MirReader.read(MirWriter.write(MirModule("t.kt", listOf(f))))
        return back.functions.single().body.map { (it as MirStmt.Eval).expr }
    }

    @Test
    fun stringsWithQuotesNewlinesAndUnicode() {
        val samples = listOf(
            "", "plain", "say \"hi\"", "back\\slash", "line1\nline2\r\n\ttab", "\u0000\u001f\u007f",
            "café üß", "€ 中文", "😀", "lone\ud800high", "lone\udc00low",
            "((paren)) ; not a comment", "\\u0041 literal", "- ", "-",
        )
        val back = roundTripConstants(samples.map { MirExpr.StringConst(it) })
        assertEquals(samples, back.map { (it as MirExpr.StringConst).value })
    }

    @Test
    fun writtenTextIsAscii() {
        val text = MirWriter.write(
            MirModule(
                "ä.kt",
                listOf(MirFunction("f", "f", emptyList(), TUnit, listOf(MirStmt.Eval(MirExpr.StringConst("€😀\n"))))),
            ),
        )
        assertTrue(text.all { it.code in 0x20..0x7e || it == '\n' }, text)
    }

    @Test
    fun doublesRoundTripExactly() {
        val samples = listOf(
            0.0, -0.0, 1.0, -1.5, 0.1, 1.0 / 3.0, 3.141592653589793, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE,
            1e300, 1.0E-300, 123456789.123456789, 2.2250738585072014E-308, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN,
        )
        val back = roundTripConstants(samples.map { MirExpr.DoubleConst(it) })
        for ((a, b) in samples.zip(back)) {
            val d = (b as MirExpr.DoubleConst).value
            if (a.isNaN()) assertTrue(d.isNaN()) else assertEquals(a.toRawBits(), d.toRawBits(), "double $a")
        }
    }

    @Test
    fun longsIntsAndChars() {
        val longs = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 4294967296L)
        val ints = listOf(0, -1, Int.MAX_VALUE, Int.MIN_VALUE)
        val chars = listOf(0, 65, 0xd800, 0xffff)
        val back = roundTripConstants(
            longs.map { MirExpr.LongConst(it) } + ints.map { MirExpr.IntConst(it) } + chars.map { MirExpr.CharConst(it) },
        )
        assertEquals(longs, back.take(longs.size).map { (it as MirExpr.LongConst).value })
        assertEquals(ints, back.drop(longs.size).take(ints.size).map { (it as MirExpr.IntConst).value })
        assertEquals(chars, back.drop(longs.size + ints.size).map { (it as MirExpr.CharConst).code })
    }

    @Test
    fun typesKeepSingletonIdentity() {
        val types = listOf(
            TInt, TLong, TBoolean, TDouble, TChar, TString, TUnit, TNothing, TNString, TNull,
            TObject("a.B", "a_B", true, true), TObject("kotlin.Any", ANY_CNAME, false), TBoxed(TInt), TBoxed(TDouble),
        )
        val back = roundTripConstants(types.map { MirExpr.NullConst(it) })
        for ((a, b) in types.zip(back)) {
            if (a is TObject || a is TBoxed) assertEquals(a, b.type) else assertTrue(a === b.type, "singleton $a")
        }
    }

    @Test
    fun malformedInputGivesFormatErrors() {
        assertFailsWith<MirFormatError> { MirReader.read("") }
        assertFailsWith<MirFormatError> { MirReader.read("(mini-mir 99 \"x\")") }
        assertFailsWith<MirFormatError> { MirReader.read("(mini-mir 1 \"x\" (fun \"a\" \"b\" (params) Bogus -))") }
        assertFailsWith<MirFormatError> { MirReader.read("(mini-mir 1 \"unterminated)") }
    }
}
