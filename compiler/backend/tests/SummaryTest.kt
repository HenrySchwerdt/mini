import mini.backend.memory.FnSummary
import mini.backend.memory.MAnnot
import mini.backend.memory.MClass
import mini.backend.memory.MExpr
import mini.backend.memory.MField
import mini.backend.memory.MFun
import mini.backend.memory.MLocal
import mini.backend.memory.MParam
import mini.backend.memory.MReturn
import mini.backend.memory.MStmt
import mini.backend.memory.MType
import mini.backend.memory.MemMode
import mini.backend.memory.Pos
import mini.backend.memory.Summaries
import mini.backend.memory.SummaryText
import mini.backend.memory.TK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inferred escape summaries (docs/memory-model.md, "Strict mode in practice"). */
class SummaryTest {
    private val cls = MClass("p.Box", MemMode.Strict, null, false)
    private val boxType = MType(TK.Strict, false, cls, null, "p.Box", "Box")
    private val holder = MClass("p.Holder", MemMode.Managed, null, false)
    private val itemField = MField("item", boxType, false, holder)
    private val p0 = Pos(1, 1)
    private var ids = 0

    private fun local(name: String) = MLocal(++ids, name, boxType)

    private fun fn(name: String, params: List<MLocal>, body: List<MStmt>): MFun {
        val f = MFun("p.$name", MemMode.Managed, params.map { MParam(it) }, MReturn(MType.OTHER))
        f.body = body
        return f
    }

    @Test
    fun readingAParameterIsABorrow() {
        val b = local("b")
        val f = fn("read", listOf(b), listOf(MStmt.Eval(MExpr.Opaque(MType.PRIM, p0, listOf(MExpr.Local(b, p0))), p0)))
        assertEquals(emptyMap(), Summaries.of(f)!!.escapes)
    }

    @Test
    fun storingReturningAndFreeingEscape() {
        val a = local("a")
        val h = local("h")
        val store = fn("store", listOf(h, a), listOf(MStmt.SetField(MExpr.Local(h, p0), itemField, MExpr.Local(a, p0), p0)))
        assertTrue(Summaries.of(store)!!.escapes(1))
        assertFalse(Summaries.of(store)!!.escapes(0))
        val c = local("c")
        val ret = fn("same", listOf(c), listOf(MStmt.Return(MExpr.Local(c, p0), 0, p0)))
        assertTrue(Summaries.of(ret)!!.escapes(0))
        val d = local("d")
        val free = fn("drop", listOf(d), listOf(MStmt.Eval(MExpr.Free(MExpr.Local(d, p0), null, mini.backend.memory.FreeKind.Free, p0), p0)))
        assertTrue(Summaries.of(free)!!.escapes(0))
    }

    @Test
    fun escapeIsTransitiveThroughCallsAndAliases() {
        val a = local("a")
        val h = local("h")
        val store = fn("store", listOf(h, a), listOf(MStmt.SetField(MExpr.Local(h, p0), itemField, MExpr.Local(a, p0), p0)))
        val x = local("x")
        val y = local("y")
        val alias = local("alias")
        val outer = fn(
            "outer", listOf(x, y),
            listOf(
                MStmt.Decl(alias, MExpr.Local(y, p0), MAnnot.NONE, p0),
                MStmt.Eval(MExpr.Call(store, null, listOf(MExpr.Local(x, p0), MExpr.Local(alias, p0)), MType.PRIM, p0), p0),
            ),
        )
        val s = Summaries.of(outer)!!
        assertFalse(s.escapes(0))
        assertTrue(s.escapes(1))
    }

    @Test
    fun recursionIsOptimisticAndTerminates() {
        val n = local("n")
        val f = fn("walk", listOf(n), emptyList())
        f.body = listOf(MStmt.Eval(MExpr.Call(f, null, listOf(MExpr.Local(n, p0)), MType.PRIM, p0), p0))
        assertEquals(emptyMap(), Summaries.of(f)!!.escapes)
    }

    @Test
    fun virtualCallTakesTheUnionOfTheOverriders() {
        val a = local("a")
        val h = local("h")
        val impl = fn("Impl.keep", listOf(h, a), listOf(MStmt.SetField(MExpr.Local(h, p0), itemField, MExpr.Local(a, p0), p0)))
        val abs = MFun("p.Api.keep", MemMode.Managed, listOf(MParam(local("h2")), MParam(local("a2"))), MReturn(MType.OTHER))
        abs.virtualTargets = { listOf(impl) }
        assertTrue(Summaries.of(abs)!!.escapes(1))
    }

    @Test
    fun unknownFunctionsHaveNoSummary() {
        val f = MFun("p.external", MemMode.Managed, listOf(MParam(local("x"))), MReturn(MType.OTHER), external = true)
        assertNull(Summaries.of(f))
        f.storedSummary = FnSummary(mapOf(0 to "stores it"))
        assertTrue(Summaries.of(f)!!.escapes(0))
    }

    @Test
    fun textRoundTrip() {
        val s = FnSummary(mapOf(1 to "stored into field `item` of `Holder`", 2 to "returned"))
        val line = SummaryText.line("p.keep/Holder,Box,Box,", s)
        assertNotNull(line)
        val back = SummaryText.parse(line + "\n")
        assertEquals(setOf(1, 2), back["p.keep/Holder,Box,Box,"]!!.escapes.keys)
        assertEquals("returned", back["p.keep/Holder,Box,Box,"]!!.escapes[2])
        assertNull(SummaryText.line("p.read/Box,", FnSummary.NONE))
    }
}
