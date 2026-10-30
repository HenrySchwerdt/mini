import mini.backend.BinaryOp
import mini.backend.CEmitter
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.RcOpt
import mini.backend.TBoolean
import mini.backend.TInt
import mini.backend.TObject
import mini.backend.TUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests of the RC elision rules (compiler/README.md, "RC elision"): hand-built MIR, the emitted C is
 * inspected. The end-to-end counterpart (programs under ASan/UBSan with leak check, exception paths) are the
 * `compiler/testdata/rcopt_*.kt` programs.
 */
class RcOptTest {
    private val a = TObject("A", "kc_A", false)
    private val an = TObject("A", "kc_A", true)

    private fun fn(name: String, params: List<MirLocal>, ret: MirType, vararg body: MirStmt, ctorOf: String? = null) =
        MirFunction(name, name, params, ret, body.toList(), ctorOf)

    private fun module(vararg f: MirFunction) = MirModule("rcopt.kt", f.toList())

    /** The C text of function [name]. */
    private fun cOf(c: String, name: String): String {
        val start = c.indexOf("/* $name */\n")
        assertTrue(start >= 0, "function $name not found in\n$c")
        val end = c.indexOf("\n}\n", start)
        return c.substring(start, end + 3)
    }

    /** True when [c] clears local [name] with a statement of its own (not the NULL initialisation of its declaration). */
    private fun clears(c: String, name: String) = Regex("(?m)^\\s+$name = NULL;").containsMatchIn(c)

    private fun retains(c: String) = Regex("mk_retain\\(|MKH_RETAIN\\(").findAll(c).count()
    private fun releases(c: String) = Regex("mk_release\\(|MKH_REL\\(").findAll(c).count()

    private fun releasesOf(c: String, v: String) = "mk_release((MkObject *)$v)" in c || "MKH_REL($v)" in c

    private fun anyRelease(c: String) = "mk_release" in c || "MKH_REL" in c

    private val read = fn("read", listOf(MirLocal(0, "p", a)), TInt, MirStmt.Return(MirExpr.IntConst(1)))
    private val mutate = fn(
        "mutate", listOf(MirLocal(0, "p", a)), TUnit,
        MirStmt.SetField(MirExpr.Local(MirLocal(0, "p", a)), "kc_A", "next", an, MirExpr.NullConst(an)),
    )

    private fun fieldNext(of: MirLocal) = MirExpr.FieldGet(MirExpr.Local(of), "kc_A", "next", an)

    private fun callRead(l: MirLocal) = MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(l)), TInt))

    // ---- borrow: parameters ----------------------------------------------------------------------------------

    @Test
    fun copyOfParameterBorrows() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, MirExpr.Local(p)), callRead(t))
        val on = cOf(CEmitter.emit(module(read, f), rcOpt = true), "f")
        val off = cOf(CEmitter.emit(module(read, f), rcOpt = false), "f")
        assertEquals(0, retains(on) + releases(on), on)
        assertEquals(1, retains(off), off)
        assertEquals(1, releases(off), off)
    }

    @Test
    fun borrowedParameterKeepsCallerAliveForTheWholeCall() {
        // a callee that overwrites a heap reference does not invalidate aliases of its own parameters
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, MirExpr.Local(p)), MirStmt.Eval(MirExpr.Call("mutate", listOf(MirExpr.Local(t)), TUnit)), callRead(t))
        val on = cOf(CEmitter.emit(module(read, mutate, f), rcOpt = true), "f")
        assertEquals(0, retains(on) + releases(on), on)
    }

    // ---- borrow: field reads ---------------------------------------------------------------------------------

    @Test
    fun fieldReadUsedOnlyByNonMutatingCallBorrows() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, fieldNext(p)), callRead(t))
        val on = cOf(CEmitter.emit(module(read, f), rcOpt = true), "f")
        assertEquals(0, retains(on) + releases(on), on)
    }

    @Test
    fun fieldReadAcrossMutatingCallIsRetained() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, fieldNext(p)), MirStmt.Eval(MirExpr.Call("mutate", listOf(MirExpr.Local(t)), TUnit)), callRead(t))
        val on = cOf(CEmitter.emit(module(read, mutate, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
        assertTrue(releases(on) >= 1, on)
    }

    @Test
    fun mutatingCallThatReceivesTheAliasMakesItOwned() {
        // the call itself may overwrite the field the alias came from while it still runs
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, fieldNext(p)), MirStmt.Eval(MirExpr.Call("mutate", listOf(MirExpr.Local(t)), TUnit)))
        val on = cOf(CEmitter.emit(module(mutate, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
    }

    @Test
    fun fieldReadAcrossFieldStoreIsRetained() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, fieldNext(p)),
            MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.NullConst(an)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
    }

    @Test
    fun fieldReadAcrossPrimitiveStoreStillBorrows() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, fieldNext(p)),
            MirStmt.SetField(MirExpr.Local(p), "kc_A", "count", TInt, MirExpr.IntConst(3)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, f), rcOpt = true), "f")
        assertEquals(0, retains(on) + releases(on), on)
    }

    @Test
    fun constructorStoresIntoThisDoNotCountAsMutation() {
        // new A(): the initialiser only fills the fresh object, so a field alias survives the allocation
        val self = MirLocal(0, "this", a)
        val ctor = fn("kc_A_C", listOf(self), TUnit, MirStmt.SetField(MirExpr.Local(self), "kc_A", "next", an, MirExpr.NullConst(an)), ctorOf = "kc_A")
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an)
        val n = MirLocal(2, "n", a)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, fieldNext(p)),
            MirStmt.Declare(n, MirExpr.New("kc_A_C", emptyList(), a)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = true), "f")
        assertEquals(0, retains(on), on)
        assertEquals(1, releases(on), on) // only the fresh object n
    }

    @Test
    fun reassignedRootEndsTheAlias() {
        val q = MirLocal(0, "q", a)
        val y = MirLocal(1, "y", a)
        val x = MirLocal(2, "x", an)
        val f = fn(
            "f", listOf(q), TUnit,
            MirStmt.Declare(y, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(x, fieldNext(y)),
            MirStmt.Assign(y, MirExpr.New("kc_A_C", emptyList(), a)),
            callRead(x),
        )
        val ctor = fn("kc_A_C", listOf(MirLocal(0, "this", a)), TUnit, ctorOf = "kc_A")
        val on = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on) // x keeps its own reference
    }

    @Test
    fun aliasOfOwnedLocalBorrowsUntilTheLocalIsReassigned() {
        val q = MirLocal(0, "q", a)
        val y = MirLocal(1, "y", a)
        val x = MirLocal(2, "x", a)
        val ctor = fn("kc_A_C", listOf(MirLocal(0, "this", a)), TUnit, ctorOf = "kc_A")
        val ok = fn(
            "ok", listOf(q), TUnit,
            MirStmt.Declare(y, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(x, MirExpr.Local(y)),
            callRead(x),
        )
        val bad = fn(
            "bad", listOf(q), TUnit,
            MirStmt.Declare(y, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(x, MirExpr.Local(y)),
            callRead(y),
            MirStmt.Assign(y, MirExpr.New("kc_A_C", emptyList(), a)),
            callRead(x),
        )
        val c = CEmitter.emit(module(read, ctor, ok, bad), rcOpt = true)
        assertEquals(0, retains(cOf(c, "ok")), cOf(c, "ok"))
        assertEquals(1, retains(cOf(c, "bad")), cOf(c, "bad"))
    }

    // ---- loops -----------------------------------------------------------------------------------------------

    @Test
    fun traversalVariableBorrowsWhenNothingMutates() {
        // var q = p; while (q != null) { read(q); q = q.next }
        val p = MirLocal(0, "p", a)
        val q = MirLocal(1, "q", an)
        val label = MirLabel(1)
        val body = listOf(
            MirStmt.Declare(q, MirExpr.Local(p)),
            MirStmt.While(
                label, MirExpr.Binary(BinaryOp.IdNe, MirExpr.Local(q), MirExpr.NullConst(an)),
                listOf(callRead(q), MirStmt.Assign(q, fieldNext(q))),
            ),
        )
        val f = fn("f", listOf(p), TUnit, *body.toTypedArray())
        val on = cOf(CEmitter.emit(module(read, f), rcOpt = true), "f")
        assertEquals(0, retains(on) + releases(on), on)
    }

    @Test
    fun traversalVariableIsOwnedWhenTheLoopMutates() {
        val p = MirLocal(0, "p", a)
        val q = MirLocal(1, "q", an)
        val label = MirLabel(1)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(q, MirExpr.Local(p)),
            MirStmt.While(
                label, MirExpr.Binary(BinaryOp.IdNe, MirExpr.Local(q), MirExpr.NullConst(an)),
                listOf(
                    MirStmt.Eval(MirExpr.Call("mutate", listOf(MirExpr.Local(p)), TUnit)),
                    MirStmt.Assign(q, fieldNext(q)),
                ),
            ),
        )
        val on = cOf(CEmitter.emit(module(mutate, f), rcOpt = true), "f")
        assertTrue(retains(on) >= 2, on) // q = p and q = q.next both retain
    }

    // ---- moves -----------------------------------------------------------------------------------------------

    private val ctor = fn("kc_A_C", listOf(MirLocal(0, "this", a)), TUnit, ctorOf = "kc_A")

    @Test
    fun lastUseInAFieldStoreMovesTheReference() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t)),
        )
        val on = cOf(CEmitter.emit(module(ctor, f), rcOpt = true), "f")
        val off = cOf(CEmitter.emit(module(ctor, f), rcOpt = false), "f")
        assertEquals(0, retains(on), on)
        // t lives in the function's own scope and is never used again: it is neither cleared nor released
        assertFalse(clears(on, "v_t_1"), on)
        assertFalse(releasesOf(on, "v_t_1"), on)
        assertEquals(1, retains(off), off)
    }

    @Test
    fun aLaterUseKeepsTheRetain() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
        assertFalse(clears(on, "v_t_1"), on)
    }

    @Test
    fun moveInOneBranchOnlyWhenTheOtherBranchUsesTheLocalLater() {
        // if (c) p.next = t else read(t)   -> the move is fine in the then branch (t is dead there)
        val p = MirLocal(0, "p", a)
        val c = MirLocal(1, "c", TBoolean)
        val t = MirLocal(2, "t", a)
        val f = fn(
            "f", listOf(p, c), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.If(
                MirExpr.Local(c),
                listOf(MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t))),
                listOf(callRead(t)),
            ),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = true), "f")
        assertEquals(0, retains(on), on)
        assertTrue(clears(on, "v_t_2"), on) // cleared so the scope end releases nothing twice
    }

    @Test
    fun useInALoopPreventsTheMove() {
        // t declared outside the loop, stored inside: the next iteration would store it again
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val label = MirLabel(1)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Loop(
                label,
                listOf(
                    MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t)),
                    MirStmt.Break(label),
                ),
            ),
        )
        val c = cOf(CEmitter.emit(module(ctor, f), rcOpt = true), "f")
        // t is dead after the store because the loop is left right away: moving is allowed here
        assertEquals(0, retains(c), c)
        val g = fn(
            "g", listOf(p), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Loop(
                label,
                listOf(
                    MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t)),
                ),
            ),
        )
        val c2 = cOf(CEmitter.emit(module(ctor, g), rcOpt = true), "g")
        assertEquals(1, retains(c2), c2)
    }

    @Test
    fun returnThroughACastMovesTheReference() {
        val t = MirLocal(0, "t", a)
        val f = fn(
            "f", emptyList(), an,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Return(MirExpr.Cast(MirExpr.Local(t), an, MirExpr.CastMode.Checked)),
        )
        val on = cOf(CEmitter.emit(module(ctor, f), rcOpt = true), "f")
        val off = cOf(CEmitter.emit(module(ctor, f), rcOpt = false), "f")
        assertEquals(0, retains(on), on)
        assertEquals(1, releases(on), on) // only the exception path of the checked cast
        assertEquals(1, retains(off), off)
    }

    // ---- exception paths ---------------------------------------------------------------------------------------

    private val thrower = fn(
        "thrower", listOf(MirLocal(0, "p", a)), TInt,
        MirStmt.Declare(MirLocal(1, "ex", a), MirExpr.RuntimeCall("MkIllegalStateException_new", listOf(MirExpr.NullConst(an), MirExpr.NullConst(an)), a)),
        MirStmt.Throw(MirExpr.Local(MirLocal(1, "ex", a))),
    )

    @Test
    fun exceptionPathReleasesOwnedButNotBorrowedLocals() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", an) // borrows (field of a parameter, nothing mutates)
        val o = MirLocal(2, "o", a) // owned
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, fieldNext(p)),
            MirStmt.Declare(o, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Eval(MirExpr.Call("thrower", listOf(MirExpr.Local(t)), TInt)),
        )
        val on = cOf(CEmitter.emit(module(ctor, thrower, f), rcOpt = true), "f")
        assertFalse(releasesOf(on, "v_t_1"), on)
        assertTrue(releasesOf(on, "v_o_2"), on)
        assertEquals(0, retains(on), on)
    }

    @Test
    fun movedLocalIsClearedBeforeAnyLaterExit() {
        // t is moved into a field, then a throwing call runs: the exit path must not release t again
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn(
            "f", listOf(p), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.SetField(MirExpr.Local(p), "kc_A", "next", an, MirExpr.Local(t)),
            MirStmt.Eval(MirExpr.Call("thrower", listOf(MirExpr.Local(p)), TInt)),
        )
        val on = cOf(CEmitter.emit(module(ctor, thrower, f), rcOpt = true), "f")
        // after the move the exit path of the throwing call must not release t again
        assertFalse(releasesOf(on, "v_t_1"), on)
        assertTrue("mk_pending_exception != NULL" in on, on)
    }

    // ---- owned constructor parameters --------------------------------------------------------------------------

    private fun ctor2() = fn(
        "kc_A_C2", listOf(MirLocal(0, "this", a), MirLocal(1, "x", a)), TUnit,
        MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.Local(MirLocal(1, "x", a))),
        ctorOf = "kc_A",
    )

    @Test
    fun constructorThatStoresItsParameterOwnsIt() {
        val c = CEmitter.emit(module(ctor2()), rcOpt = true)
        val body = cOf(c, "kc_A_C2")
        assertEquals(0, retains(body), body) // the parameter's reference moves into the field
        assertFalse(releasesOf(body, "v_x_1"), body)
        val base = cOf(CEmitter.emit(module(ctor2()), rcOpt = false), "kc_A_C2")
        assertEquals(1, retains(base), base)
    }

    @Test
    fun callerHandsItsLastReferenceToTheConstructor() {
        val t = MirLocal(0, "t", a)
        val r = MirLocal(1, "r", a)
        val f = fn(
            "f", emptyList(), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(r, MirExpr.New("kc_A_C2", listOf(MirExpr.Local(t)), a)),
            callRead(r),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, ctor2(), f), rcOpt = true), "f")
        assertEquals(0, retains(on), on)
        assertFalse(releasesOf(on, "v_t_0"), on)
        assertTrue(releasesOf(on, "v_r_1"), on)
    }

    @Test
    fun callerRetainsAReferenceItStillNeeds() {
        val t = MirLocal(0, "t", a)
        val r = MirLocal(1, "r", a)
        val f = fn(
            "f", emptyList(), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(r, MirExpr.New("kc_A_C2", listOf(MirExpr.Local(t)), a)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, ctor2(), f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
        assertTrue(releasesOf(on, "v_t_0"), on)
    }

    @Test
    fun callerRetainsABorrowedArgument() {
        val p = MirLocal(0, "p", a)
        val r = MirLocal(1, "r", a)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(r, MirExpr.New("kc_A_C2", listOf(MirExpr.Local(p)), a)))
        val on = cOf(CEmitter.emit(module(ctor2(), f), rcOpt = true), "f")
        assertEquals(1, retains(on), on)
    }

    @Test
    fun parameterConsumedOnlyOnSomePathsStaysBorrowed() {
        val c3 = fn(
            "kc_A_C3", listOf(MirLocal(0, "this", a), MirLocal(1, "x", a), MirLocal(2, "c", TBoolean)), TUnit,
            MirStmt.If(
                MirExpr.Local(MirLocal(2, "c", TBoolean)),
                listOf(MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.Local(MirLocal(1, "x", a)))),
                emptyList(),
            ),
            ctorOf = "kc_A",
        )
        val body = cOf(CEmitter.emit(module(c3), rcOpt = true), "kc_A_C3")
        assertEquals(1, retains(body), body)
    }

    @Test
    fun constructorThatThrowsBeforeConsumingStillReleasesItsParameter() {
        val c4 = fn(
            "kc_A_C4", listOf(MirLocal(0, "this", a), MirLocal(1, "x", a)), TUnit,
            MirStmt.Eval(MirExpr.Call("thrower", listOf(MirExpr.Local(MirLocal(0, "this", a))), TInt)),
            MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.Local(MirLocal(1, "x", a))),
            ctorOf = "kc_A",
        )
        val body = cOf(CEmitter.emit(module(thrower, c4), rcOpt = true), "kc_A_C4")
        // exit path of the throwing call: x is still owned there
        assertTrue(releasesOf(body, "v_x_1"), body)
        assertEquals(0, retains(body), body)
    }

    @Test
    fun aliasOfAnOwnedLocalPassesItsReferenceOn() {
        // val t = A(); val a = t (alias); B(a): the constructor takes t's reference through the alias
        val t = MirLocal(0, "t", a)
        val al = MirLocal(1, "al", a)
        val r = MirLocal(2, "r", a)
        val f = fn(
            "f", emptyList(), TUnit,
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(al, MirExpr.Local(t)),
            MirStmt.Declare(r, MirExpr.New("kc_A_C2", listOf(MirExpr.Local(al)), a)),
            callRead(r),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, ctor2(), f), rcOpt = true), "f")
        assertEquals(0, retains(on), on)
        assertFalse(releasesOf(on, "v_t_0"), on)
    }

    @Test
    fun exitsBeforeADeclarationDoNotReleaseTheLocal() {
        val c = MirLocal(0, "c", TBoolean)
        val t = MirLocal(1, "t", a)
        val f = fn(
            "f", listOf(c), TUnit,
            MirStmt.If(MirExpr.Local(c), listOf(MirStmt.Return(null)), emptyList()),
            MirStmt.Declare(t, MirExpr.New("kc_A_C", emptyList(), a)),
            callRead(t),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = true), "f")
        val off = cOf(CEmitter.emit(module(read, ctor, f), rcOpt = false), "f")
        val firstReturn = on.indexOf("return;")
        assertFalse(anyRelease(on.substring(0, firstReturn)), on)
        assertTrue(anyRelease(off.substring(0, off.indexOf("return;"))), off)
        assertTrue(releasesOf(on, "v_t_1"), on) // the normal end still releases it
    }

    @Test
    fun firstStoreIntoAFreshObjectNeedsNoRelease() {
        val body = cOf(CEmitter.emit(module(ctor2()), rcOpt = true), "kc_A_C2")
        assertFalse(anyRelease(body), body)
        assertFalse("old" in body, body)
        // a second store into the same field has to release the first value
        val two = fn(
            "kc_A_C5", listOf(MirLocal(0, "this", a), MirLocal(1, "x", a)), TUnit,
            MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.NullConst(an)),
            MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.Local(MirLocal(1, "x", a))),
            ctorOf = "kc_A",
        )
        val b2 = cOf(CEmitter.emit(module(two), rcOpt = true), "kc_A_C5")
        assertEquals(1, releases(b2), b2) // only the second store releases an old value
    }

    @Test
    fun storeAfterThisHasLeakedStillReleases() {
        val leaky = fn(
            "kc_A_C6", listOf(MirLocal(0, "this", a), MirLocal(1, "x", a)), TUnit,
            MirStmt.Eval(MirExpr.Call("read", listOf(MirExpr.Local(MirLocal(0, "this", a))), TInt)),
            MirStmt.SetField(MirExpr.Local(MirLocal(0, "this", a)), "kc_A", "next", an, MirExpr.Local(MirLocal(1, "x", a))),
            ctorOf = "kc_A",
        )
        val body = cOf(CEmitter.emit(module(read, leaky), rcOpt = true), "kc_A_C6")
        assertTrue(releasesOf(body, "old"), body)
    }

    @Test
    fun assigningTheResultOfAConstructorFedByTheSameLocalNeedsNoCounting() {
        // x = B(x) in a loop-free form
        val x = MirLocal(0, "x", a)
        val f = fn(
            "f", emptyList(), TUnit,
            MirStmt.Declare(x, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Assign(x, MirExpr.New("kc_A_C2", listOf(MirExpr.Local(x)), a)),
            callRead(x),
        )
        val on = cOf(CEmitter.emit(module(read, ctor, ctor2(), f), rcOpt = true), "f")
        assertEquals(0, retains(on), on)
        assertFalse("old" in on, on)
    }

    @Test
    fun copyThatIsSometimesNullDoesNotGiveTheOwnerAway() {
        // val r = A(); val res = if (c) r else null; return res: on the null path r still has to be released
        val c = MirLocal(0, "c", TBoolean)
        val r = MirLocal(1, "r", a)
        val res = MirLocal(2, "res", an)
        val f = fn(
            "f", listOf(c), an,
            MirStmt.Declare(r, MirExpr.New("kc_A_C", emptyList(), a)),
            MirStmt.Declare(res, null),
            MirStmt.If(
                MirExpr.Local(c),
                listOf(MirStmt.Assign(res, MirExpr.Local(r))),
                listOf(MirStmt.Assign(res, MirExpr.NullConst(an))),
            ),
            MirStmt.Return(MirExpr.Local(res)),
        )
        val on = cOf(CEmitter.emit(module(ctor, f), rcOpt = true), "f")
        assertEquals(1, retains(on), on) // the result keeps its own reference
        assertTrue(releasesOf(on, "v_r_1"), on)
    }

    // ---- the pass itself -----------------------------------------------------------------------------------------

    @Test
    fun analysisIsDeterministicAndCountsItsDecisions() {
        val p = MirLocal(0, "p", a)
        val t = MirLocal(1, "t", a)
        val f = fn("f", listOf(p), TUnit, MirStmt.Declare(t, MirExpr.Local(p)), callRead(t))
        val m = module(read, f)
        val s1 = RcOpt.analyze(m).stats
        val s2 = RcOpt.analyze(m).stats
        assertEquals(1, s1.borrowedLocals)
        assertEquals(s1.borrowedLocals, s2.borrowedLocals)
        assertEquals(CEmitter.emit(m, rcOpt = true), CEmitter.emit(m, rcOpt = true))
    }
}
