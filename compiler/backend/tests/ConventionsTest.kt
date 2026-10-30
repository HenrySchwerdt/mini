import mini.backend.CEmitter
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirField
import mini.backend.MirFunction
import mini.backend.MirItable
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirReader
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.MirWriter
import mini.backend.TInt
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests of the calling conventions (borrowed results, owned parameters, compiler/README.md "Conventions") and of
 * the heap effects that decide how long a borrowed alias stays valid (Effects.kt): hand-built MIR, the emitted C is
 * inspected. End to end the conventions run in every stdlib program of `compiler/testdata` (ASan/UBSan, leak check).
 */
class ConventionsTest {
    private val a = TObject("A", "kc_A", false)
    private val an = TObject("A", "kc_A", true)
    private val any = TObject("kotlin.Any", "mk_Any", true)

    private val classA = MirClass(
        "A", "kc_A", false, null, listOf(MirField("next", an), MirField("other", an), MirField("name", TString), MirField("n", TInt)),
        emptyList(), listOf(null, null, null, "A_get", "A_put"), emptyList(),
    )

    private fun fn(
        name: String, params: List<MirLocal>, ret: MirType, vararg body: MirStmt,
        borrowed: Boolean = false, owns: List<Int> = emptyList(),
    ) = MirFunction(name, name, params, ret, body.toList(), null, false, borrowed, owns)

    private fun module(vararg f: MirFunction, classes: List<MirClass> = listOf(classA)) = MirModule("conv.kt", f.toList(), classes)

    private fun cOf(c: String, name: String): String {
        val start = c.indexOf("/* $name */\n")
        assertTrue(start >= 0, "function $name not found in\n$c")
        val end = c.indexOf("\n}\n", start)
        return c.substring(start, end + 3)
    }

    private fun retains(c: String) = occurrences(c, "mk_retain(", "MKH_RETAIN(")
    private fun releases(c: String) = occurrences(c, "mk_release(", "MKH_REL(")

    private fun p(id: Int, name: String, t: MirType = a) = MirLocal(id, name, t)

    // ---- owned parameters --------------------------------------------------------------------------------------

    /** `fun sink(x: @TakesOwnership A) { this.next = x }` style: stores its owned parameter into a field of a global holder. */
    private val holder = p(0, "h")
    private val sinkX = p(1, "x")
    private val sink = fn(
        "sink", listOf(holder, sinkX), TUnit,
        MirStmt.SetField(MirExpr.Local(holder), "kc_A", "next", an, MirExpr.Local(sinkX)),
        owns = listOf(1),
    )

    @Test
    fun callerHandsItsLastReferenceToAnOwnedParameter() {
        val h = p(0, "h")
        val t = p(1, "t")
        val f = fn(
            "f", listOf(h), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Eval(MirExpr.Call("sink", listOf(MirExpr.Local(h), MirExpr.Local(t)), TUnit)),
        )
        val ctor = MirFunction("kc_A_C", "kc_A_C", listOf(p(0, "this")), TUnit, emptyList(), "kc_A")
        val on = cOf(CEmitter.emit(module(ctor, sink, f)), "f")
        assertEquals(0, retains(on), on) // moved into the callee
        assertFalse(releasesLocal(on, "v_t_1"), on)
        val sinkC = cOf(CEmitter.emit(module(ctor, sink, f)), "sink")
        assertEquals(0, retains(sinkC), sinkC) // and moved on into the field
        // without conventions: the callee borrows, the caller keeps its reference and the field store retains
        val off = CEmitter.emit(module(ctor, sink, f), conventions = false)
        assertEquals(1, retains(cOf(off, "sink")), cOf(off, "sink"))
        assertTrue(releases(cOf(off, "f")) >= 1, cOf(off, "f"))
    }

    @Test
    fun anOwnedParameterThatIsNotConsumedIsReleasedByTheCallee() {
        val x = p(1, "x")
        val ignore = fn("ignore", listOf(p(0, "h"), x), TUnit, MirStmt.Return(null), owns = listOf(1))
        val c = cOf(CEmitter.emit(module(ignore)), "ignore")
        assertTrue(releases(c) >= 1, c)
    }

    // ---- borrowed results -----------------------------------------------------------------------------------------

    private val getH = p(0, "h")
    private val getter = fn(
        "getter", listOf(getH), an,
        MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(getH), "kc_A", "other", an)),
        borrowed = true,
    )

    @Test
    fun aBorrowedResultNeedsNoCountsOnEitherSide() {
        val h = p(0, "h")
        val t = p(1, "t", an)
        val f = fn(
            "f", listOf(h), TInt,
            MirStmt.Declare(t, MirExpr.Call("getter", listOf(MirExpr.Local(h)), an)),
            MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(t), "kc_A", "n", TInt)),
        )
        val c = CEmitter.emit(module(getter, f))
        assertEquals(0, retains(cOf(c, "getter")) + releases(cOf(c, "getter")), cOf(c, "getter"))
        assertEquals(0, retains(cOf(c, "f")) + releases(cOf(c, "f")), cOf(c, "f"))
        // the baseline convention: the callee retains the result, the caller releases it
        val base = CEmitter.emit(module(getter, f), conventions = false)
        assertEquals(1, retains(cOf(base, "getter")), cOf(base, "getter"))
        assertTrue(releases(cOf(base, "f")) >= 1, cOf(base, "f"))
    }

    @Test
    fun aBorrowedResultIsRetainedWhenAHeapWriteMayInvalidateIt() {
        val h = p(0, "h")
        val t = p(1, "t", an)
        val f = fn(
            "f", listOf(h), TInt,
            MirStmt.Declare(t, MirExpr.Call("getter", listOf(MirExpr.Local(h)), an)),
            MirStmt.SetField(MirExpr.Local(h), "kc_A", "other", an, MirExpr.NullConst(an)), // the field the result was read from
            MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(t), "kc_A", "n", TInt)),
        )
        val c = cOf(CEmitter.emit(module(getter, f)), "f")
        assertEquals(1, retains(c), c)
    }

    @Test
    fun aFunctionThatReturnsAFreshObjectDropsTheRequest() {
        val bad = fn("bad", listOf(p(0, "h")), an, MirStmt.Return(MirExpr.New("kc_A_C", emptyList(), a)), borrowed = true)
        val ctor = MirFunction("kc_A_C", "kc_A_C", listOf(p(0, "this")), TUnit, emptyList(), "kc_A")
        val r = CEmitter.emitDetailed(module(ctor, bad))
        assertFalse(r.plan!!.returnsBorrowed("bad"))
        assertTrue(r.plan!!.conv.summary().any { "dropped" in it }, r.plan!!.conv.summary().toString())
        // and the function returns the +1 object like before
        assertEquals(0, releases(cOf(r.c, "bad")), cOf(r.c, "bad"))
    }

    @Test
    fun theConventionHoldsForAWholeDispatchGroup() {
        // two classes implement slot 4 `get`; one honours the request, the other returns a fresh object: the group is dropped for both
        val good = fn("A_get", listOf(p(0, "this")), an, MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(p(0, "this")), "kc_A", "next", an)), borrowed = true)
        val bad = fn("B_get", listOf(p(0, "this")), an, MirStmt.Return(MirExpr.New("kc_A_C", emptyList(), a)))
        val ctor = MirFunction("kc_A_C", "kc_A_C", listOf(p(0, "this")), TUnit, emptyList(), "kc_A")
        val classB = MirClass("B", "kc_B", false, "kc_A", emptyList(), emptyList(), listOf(null, null, null, "B_get", null), emptyList())
        val r = CEmitter.emitDetailed(module(ctor, good, bad, classes = listOf(classA, classB)))
        assertFalse(r.plan!!.returnsBorrowed("A_get"), r.plan!!.conv.summary().toString())
        assertFalse(r.plan!!.returnsBorrowed("B_get"))
        val onlyGood = CEmitter.emitDetailed(module(ctor, good, classes = listOf(classA)))
        assertTrue(onlyGood.plan!!.returnsBorrowed("A_get"), onlyGood.plan!!.conv.summary().toString())
    }

    // ---- heap effects -------------------------------------------------------------------------------------------------

    private val read = fn("read", listOf(p(0, "p")), TInt, MirStmt.Return(MirExpr.IntConst(1)))

    private fun storeInto(obj: MirLocal, field: String) =
        MirStmt.SetField(MirExpr.Local(obj), "kc_A", field, an, MirExpr.NullConst(an))

    @Test
    fun aStoreIntoAnotherFieldDoesNotInvalidateAnAlias() {
        val q = p(0, "q")
        val t = p(1, "t", an)
        val f = fn(
            "f", listOf(q), TUnit,
            MirStmt.Declare(t, MirExpr.FieldGet(MirExpr.Local(q), "kc_A", "next", an)),
            storeInto(q, "other"),
            MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(t)), TInt)),
        )
        val c = cOf(CEmitter.emit(module(read, f)), "f")
        assertEquals(0, retains(c), c)
    }

    @Test
    fun aStoreIntoTheSameFieldOfAnyObjectInvalidatesIt() {
        val q = p(0, "q")
        val r = p(2, "r")
        val t = p(1, "t", an)
        val f = fn(
            "f", listOf(q, r), TUnit,
            MirStmt.Declare(t, MirExpr.FieldGet(MirExpr.Local(q), "kc_A", "next", an)),
            storeInto(r, "next"),
            MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(t)), TInt)),
        )
        val c = cOf(CEmitter.emit(module(read, f)), "f")
        assertEquals(1, retains(c), c)
    }

    @Test
    fun theEffectOfACalleeCountsTransitively() {
        val g = fn("g", listOf(p(0, "p")), TUnit, storeInto(p(0, "p"), "next"))
        val h = fn("h", listOf(p(0, "p")), TUnit, MirStmt.Eval(MirExpr.Call("g", listOf(MirExpr.Local(p(0, "p"))), TUnit)))
        val q = p(0, "q")
        val t = p(1, "t", an)
        val f = fn(
            "f", listOf(q), TUnit,
            MirStmt.Declare(t, MirExpr.FieldGet(MirExpr.Local(q), "kc_A", "next", an)),
            MirStmt.Eval(MirExpr.Call("h", listOf(MirExpr.Local(q)), TUnit)),
            MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(t)), TInt)),
        )
        val c = cOf(CEmitter.emit(module(read, g, h, f)), "f")
        assertEquals(1, retains(c), c)
    }

    @Test
    fun aStoreIntoAFieldOfAFreshObjectInItsConstructorIsNoEffect() {
        val self = p(0, "this")
        val ctor = MirFunction(
            "kc_A_C", "kc_A_C", listOf(self), TUnit, listOf(storeInto(self, "next")), "kc_A",
        )
        val q = p(0, "q")
        val t = p(1, "t", an)
        val n = p(2, "n")
        val f = fn(
            "f", listOf(q), TUnit,
            MirStmt.Declare(t, MirExpr.FieldGet(MirExpr.Local(q), "kc_A", "next", an)),
            MirStmt.Declare(n, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(t)), TInt)),
        )
        val c = cOf(CEmitter.emit(module(read, ctor, f)), "f")
        assertEquals(0, retains(c), c)
    }

    // ---- the MIR format -------------------------------------------------------------------------------------------------

    @Test
    fun conventionFlagsSurviveTheMirFormat() {
        val m = module(sink, getter)
        val text = MirWriter.write(m)
        val back = MirReader.read(text)
        assertEquals(text, MirWriter.write(back))
        assertEquals(listOf(1), back.functions.first { it.cName == "sink" }.ownsParams)
        assertTrue(back.functions.first { it.cName == "getter" }.returnsBorrowed)
        assertEquals(CEmitter.emit(m), CEmitter.emit(back))
    }

    @Test
    fun anOwnedRuntimeArgumentIsRetainedUnlessItsLastUseIsTheCall() {
        val arr = p(0, "arr", any)
        val x = p(1, "x", any)
        val set = MirStmt.Eval(MirExpr.RuntimeCall("mk_ref_array_set_o", listOf(MirExpr.Local(arr), MirExpr.IntConst(0), MirExpr.Local(x)), TUnit))
        val keep = fn("keep", listOf(arr, x), TUnit, set, MirStmt.Eval(MirExpr.Call("use", listOf(MirExpr.Local(x)), TUnit)))
        val use = fn("use", listOf(p(0, "x", any)), TUnit, MirStmt.Return(null))
        val c = cOf(CEmitter.emit(module(use, keep)), "keep")
        assertEquals(1, retains(c), c) // x is used afterwards: the array gets its own reference
        val consume = fn("consume", listOf(arr, x), TUnit, set)
        val c2 = cOf(CEmitter.emit(module(consume)), "consume")
        assertEquals(1, retains(c2), c2) // x is a borrowed parameter: retained for the store
    }

    @Test
    fun anItableEntryOfAnotherGroupIsNotAffected() {
        // ICall groups are keyed by interface and index: a conforming `next` is not dropped by an unrelated interface method
        val iface = MirClass("I", "kc_I", true, null, emptyList(), emptyList(), emptyList(), emptyList())
        val impl = fn("impl_next", listOf(p(0, "this")), an, MirStmt.Return(MirExpr.FieldGet(MirExpr.Local(p(0, "this")), "kc_A", "next", an)), borrowed = true)
        val other = fn("impl_other", listOf(p(0, "this")), an, MirStmt.Return(MirExpr.New("kc_A_C", emptyList(), a)))
        val ctor = MirFunction("kc_A_C", "kc_A_C", listOf(p(0, "this")), TUnit, emptyList(), "kc_A")
        val clazz = MirClass(
            "C", "kc_C", false, null, emptyList(), listOf("kc_I", "kc_J"), listOf(null, null, null), listOf(MirItable("kc_I", listOf("impl_next")), MirItable("kc_J", listOf("impl_other"))),
        )
        val r = CEmitter.emitDetailed(module(ctor, impl, other, classes = listOf(classA, iface, clazz)))
        assertTrue(r.plan!!.returnsBorrowed("impl_next"), r.plan!!.conv.summary().toString())
    }
}
