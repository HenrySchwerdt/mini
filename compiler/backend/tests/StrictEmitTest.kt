import mini.backend.CEmitter
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirField
import mini.backend.MirFunction
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirReader
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.MirWriter
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests of the Strict code generation (compiler/README.md, "Strict code generation"): hand-built MIR with one Strict
 * class (`Node`: an owned tracked field, a String, a `@Borrowed` String) and one Managed class, the emitted C is inspected.
 * The end-to-end counterparts (ASan/UBSan, leak check) are `compiler/testdata/strict_*.kt` and the memory corpus.
 */
class StrictEmitTest {
    private val node = TObject("S.Node", "kc_Node", false)
    private val nodeN = TObject("S.Node", "kc_Node", true)
    private val box = TObject("S.Box", "kc_Box", false)
    private val arena = TObject("mini.memory.Arena", "kc_Arena", false)

    private fun cls(name: String, strict: Boolean, vararg fields: MirField) = MirClass(
        "S.$name", "kc_$name", false, null, fields.toList(), emptyList(), listOf(null, null, null), emptyList(), strict = strict,
    )

    private val classes = listOf(
        cls("Node", true, MirField("next", nodeN), MirField("name", TString), MirField("label", TString, borrowed = true), MirField("v", TInt)),
        cls("Box", false, MirField("held", nodeN)),
        cls("Arena", false, MirField("handle", TLong)),
    )

    private fun ctor(strict: Boolean): MirFunction {
        val self = MirLocal(0, "this", node)
        val name = MirLocal(1, "name", TString)
        val label = MirLocal(2, "label", TString)
        val next = MirLocal(3, "next", nodeN)
        return MirFunction(
            "kc_Node_C", "kc_Node_C", listOf(self, name, label, next), TUnit,
            listOf(
                MirStmt.SetField(MirExpr.Local(self), "kc_Node", "name", TString, MirExpr.Local(name)),
                MirStmt.SetField(MirExpr.Local(self), "kc_Node", "label", TString, MirExpr.Local(label)),
                MirStmt.SetField(MirExpr.Local(self), "kc_Node", "next", nodeN, MirExpr.Local(next)),
            ),
            constructorOf = "kc_Node", strict = strict,
        )
    }

    private fun module(vararg f: MirFunction) = MirModule("strict.kt", f.toList() + ctor(true), classes)

    private fun cOf(c: String, name: String): String {
        val start = c.indexOf("/* $name */\n")
        assertTrue(start >= 0, "function $name not found in\n$c")
        val end = c.indexOf("\n}\n", start)
        return c.substring(start, end + 3)
    }

    private fun counts(c: String) = Regex("mk_retain\\(|MKH_RETAIN\\(").findAll(c).count() to Regex("mk_release\\(|MKH_REL\\(").findAll(c).count()

    private val p = MirLocal(0, "p", node)
    private val ar = MirLocal(1, "a", arena)

    /** `fun f(a: Arena, p: Node): Int { val n = Node("x", "l", p) in a; val m = n; return m.v }`: tracked locals, no counts. */
    private fun build(strict: Boolean): MirFunction {
        val n = MirLocal(2, "n", node)
        val m = MirLocal(3, "m", node)
        val handle = MirExpr.FieldGet(MirExpr.Local(ar), "kc_Arena", "handle", TLong)
        return MirFunction(
            "f", "f", listOf(ar, p), TInt,
            listOf(
                MirStmt.Declare(n, MirExpr.New("kc_Node_C", listOf(MirExpr.StringConst("x"), MirExpr.StringConst("l"), MirExpr.Local(p)), node, handle)),
                MirStmt.Declare(m, MirExpr.Local(n)),
                MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(m), "kc_Node", "v", TInt)),
            ),
            strict = strict,
        )
    }

    @Test
    fun trackedValuesInStrictFunctionsHaveNoCounts() {
        val c = CEmitter.emit(module(build(true)))
        val f = cOf(c, "f")
        assertEquals(0 to 0, counts(f), f)
        assertTrue("_newa(" in f, f)
        assertTrue("MkObject * v_n_2 = " in f, f) // a plain local, not NULL-initialised and released
    }

    @Test
    fun withoutStrictCodegenEverythingIsCounted() {
        val c = CEmitter.emit(module(build(true)), strict = false)
        val f = cOf(c, "f")
        assertFalse("_newa(" in f, f)
        assertTrue(counts(f).second >= 1, f)
    }

    @Test
    fun managedFunctionsKeepCountsForStrictClasses() {
        val c = CEmitter.emit(module(build(false)))
        val f = cOf(c, "f")
        assertFalse("_newa(" in f, f)
        assertTrue(counts(f).second >= 1, f)
    }

    @Test
    fun disposeFreesOwnedFieldsAndLeavesBorrowedOnesAlone() {
        val c = CEmitter.emit(module(build(true)))
        val d = c.substring(c.indexOf("static void kc_Node_dispose"), c.indexOf("static void kc_Node_dispose") + 400)
        assertTrue("mk_free_owned((MkObject *)o->f_next)" in d, d)
        assertTrue("o->f_name" in d, d)
        assertFalse("f_label" in d, d)
    }

    @Test
    fun borrowedFieldStoreDoesNotRetain() {
        val c = CEmitter.emit(module(build(true)))
        val k = cOf(c, "kc_Node_C")
        assertTrue("f_label" in k, k)
        // `name` is stored with a count (moved into the field by RcOpt or retained), `label` and the tracked `next` are plain stores
        assertFalse(Regex("MKH_RETAIN\\(.*label").containsMatchIn(k), k)
        assertEquals(0, Regex("mk_retain|MKH_RETAIN").findAll(k.substringAfter("f_name")).count(), k)
    }

    @Test
    fun freeIsARuntimeCallAndAutoLocalsAreReleased() {
        val x = MirLocal(0, "x", node)
        val y = MirLocal(1, "y", node)
        val f = MirFunction(
            "g", "g", emptyList(), TUnit,
            listOf(
                MirStmt.Declare(x, MirExpr.New("kc_Node_C", listOf(MirExpr.StringConst("x"), MirExpr.StringConst("l"), MirExpr.NullConst(nodeN)), node, null, auto = true)),
                MirStmt.Declare(y, MirExpr.Local(x)),
                MirStmt.Eval(MirExpr.RuntimeCall("mk_free_dealloc", listOf(MirExpr.Local(y)), TUnit)),
            ),
            strict = true,
        )
        val c = CEmitter.emit(module(f))
        val g = cOf(c, "g")
        assertTrue("mk_free_dealloc(v_y_1)" in g, g)
        // the auto local x keeps a count that is released at the end of the scope, the alias y has none
        assertTrue(Regex("(MKH_REL|mk_release)\\(\\(?v_x_0\\)?\\)|mk_release\\(\\(MkObject \\*\\)v_x_0\\)").containsMatchIn(g), g)
        assertFalse(g.contains("(v_y_1)") && Regex("(MKH_REL|mk_release).*v_y_1").containsMatchIn(g), g)
        val off = cOf(CEmitter.emit(module(f), strict = false), "g")
        assertFalse("mk_free_dealloc" in off, off)
    }

    @Test
    fun strictFlagsSurviveTheMirFormat() {
        val m = module(build(true))
        val text = MirWriter.write(m)
        val back = MirReader.read(text)
        assertEquals(text, MirWriter.write(back))
        assertTrue(back.classes.any { it.cName == "kc_Node" && it.strict })
        assertTrue(back.classes.first { it.cName == "kc_Node" }.fields.any { it.name == "label" && it.borrowed })
        assertTrue(back.functions.first { it.cName == "f" }.strict)
        assertEquals(CEmitter.emit(m), CEmitter.emit(back))
    }

    @Test
    fun memReportClassifiesAllocationsAndReasons() {
        val m = module(build(true))
        val r = CEmitter.emitDetailed(m)
        val rep = mini.backend.MemReport.build(m, r)
        val f = rep.functions.first { it.cName == "f" }
        assertEquals(1, f.allocs["arena"], f.allocs.toString())
        assertEquals(0, f.rcSites, f.toString())
        val text = mini.backend.MemReport.text(rep)
        assertTrue("allocation sites: " in text && "arena 1" in text, text)
        // the same module without Strict code generation: reference counting and counted allocations everywhere
        val base = mini.backend.MemReport.build(m, CEmitter.emitDetailed(m, strict = false))
        val g = base.functions.first { it.cName == "f" }
        assertTrue(g.rcSites > 0 && g.rcAllocs == 1, g.toString())
        val warnings = mini.backend.MemReport.m0207(base) { it.cName == "f" }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].startsWith("warning[M0207]: reference counting remains in `f`"), warnings[0])
        assertTrue("help:" in warnings[0], warnings[0])
    }
}
