import mini.backend.BinaryOp
import mini.backend.CEmitter
import mini.backend.Mangling
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.TInt
import mini.backend.TString
import mini.backend.TUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmitterTest {
    @Test
    fun mangling() {
        assertEquals("mk_main", Mangling.function("", "main", emptyList()))
        assertEquals("mk_main", Mangling.function("a.b", "main", emptyList()))
        assertEquals("kt_foo", Mangling.function("", "foo", emptyList()))
        assertEquals("kt_a_Db_Dfoo_AI", Mangling.function("a.b", "foo", listOf(TInt)))
        assertEquals("kt_a_Db_Dfoo_AIS", Mangling.function("a.b", "foo", listOf(TInt, TString)))
        assertEquals("kt_my_ufun", Mangling.function("", "my_fun", emptyList()))
        assertEquals("kt_x_x00E9", Mangling.function("", "xé", emptyList()))
        // injective: a package segment "b" plus name "c" is not the identifier "b_c"
        val a = Mangling.function("b", "c", emptyList())
        val b = Mangling.function("", "b_c", emptyList())
        assertTrue(a != b)
        // overloads differ
        assertTrue(Mangling.function("", "f", listOf(TInt)) != Mangling.function("", "f", listOf(TString)))
    }

    private fun module(vararg f: MirFunction) = MirModule("test.kt", f.toList())

    @Test
    fun intArithmeticUsesUnsignedWrap() {
        val x = MirLocal(0, "x", TInt)
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(
                MirStmt.Declare(x, MirExpr.IntConst(Int.MAX_VALUE)),
                MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Binary(BinaryOp.Add, MirExpr.Local(x), MirExpr.IntConst(1))), TUnit)),
            ),
        )
        val c = CEmitter.emit(module(main))
        assertTrue("(MkInt)((uint32_t)v_x_0 + (uint32_t)1)" in c, c)
        assertTrue("void mk_main(void) {" in c)
    }

    @Test
    fun externDeclarationsUseThePlatformLabelPrefix() {
        // On Mach-O the C symbol `atoi` is `_atoi`: the asm label must carry __USER_LABEL_PREFIX__ (empty on ELF)
        val main = MirFunction("main", "mk_main", emptyList(), TUnit, emptyList())
        val m = MirModule("test.kt", listOf(main), externs = listOf(mini.backend.MirExtern("atoi", listOf(TInt), TInt)))
        val c = CEmitter.emit(m)
        assertTrue("#define MKX_SYM(s) MKX_STR(__USER_LABEL_PREFIX__) s" in c, c)
        assertTrue("__asm__(MKX_SYM(\"atoi\"));" in c, c)
        assertFalse("__asm__(\"atoi\")" in c, c)
    }

    @Test
    fun divisionGoesThroughCheckedHelper() {
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Binary(BinaryOp.Div, MirExpr.IntConst(1), MirExpr.IntConst(0))), TUnit))),
        )
        val c = CEmitter.emit(module(main))
        assertTrue("mkh_div_int(1, 0)" in c)
        assertTrue("/ by zero" in c)
    }

    @Test
    fun stringLiteralsAreUtf16AndDeduplicated() {
        val s = MirExpr.StringConst("a\"b\\é\n")
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(
                MirStmt.Eval(MirExpr.RuntimeCall("mk_println_string", listOf(s), TUnit)),
                MirStmt.Eval(MirExpr.RuntimeCall("mk_println_string", listOf(MirExpr.StringConst("a\"b\\é\n")), TUnit)),
            ),
        )
        val c = CEmitter.emit(module(main))
        assertEquals(1, Regex("MK_STRING_LITERAL\\(").findAll(c).count())
        assertTrue("u\"a\\\"b\\\\\\x00E9\" u\"" in c, c)
        assertTrue("\\x000A" in c)
    }

    @Test
    fun unusedLabelsAreNotEmitted() {
        val l = MirLabel(7)
        val main = MirFunction(
            "main", "mk_main", emptyList(), TUnit,
            listOf(MirStmt.Loop(l, listOf(MirStmt.Break(l)))),
        )
        val c = CEmitter.emit(module(main))
        assertTrue("brk_7:;" in c)
        assertFalse("cont_7" in c)
    }

    @Test
    fun nonUnitFunctionGetsUnreachableGuard() {
        val f = MirFunction(
            "f", "kt_f", emptyList(), TInt,
            listOf(MirStmt.Loop(MirLabel(0), emptyList())),
        )
        val c = CEmitter.emit(module(f))
        assertTrue("mk_panic(\"unreachable" in c)
    }
}
