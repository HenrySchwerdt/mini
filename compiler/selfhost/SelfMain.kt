package selfhost

import mini.backend.*
import mini.backend.memory.*

/*
 * v1-A driver: exercises the pure-Kotlin backend (`compiler/backend`). The same sources run on the JVM (real Kotlin)
 * and natively (compiled by minic together with the backend, see SelfHostTest); both must print exactly the same.
 *
 * What it does:
 *  1. builds MIR modules by hand (every statement and expression form: arithmetic and conversions, loops, strings,
 *     classes with vtables/itables, objects, boxing, casts, exceptions) and prints the C that `CEmitter` makes;
 *  2. builds a few hundred pseudo-random but well-formed MIR functions (deterministic LCG, so JVM and native see
 *     the same programs) and prints their C as well;
 *  3. builds MemIr programs for the Strict-mode ownership checker (handcrafted and pseudo-random) and prints the
 *     diagnostics `MemoryChecker` finds.
 */

// ---- deterministic random numbers ---------------------------------------------------------------------------

private class Rng(seed: Long) {
    private var s = seed

    fun next(): Int {
        s = s * 6364136223846793005L + 1442695040888963407L
        return (s ushr 33).toInt()
    }

    fun below(n: Int): Int = next() % n

    fun chance(percent: Int): Boolean = below(100) < percent

    fun <T> pick(xs: List<T>): T = xs[below(xs.size)]
}

// ---- small MIR builders -------------------------------------------------------------------------------------

private fun ci(n: Int): MirExpr = MirExpr.IntConst(n)
private fun cl(n: Long): MirExpr = MirExpr.LongConst(n)
private fun cd(n: Double): MirExpr = MirExpr.DoubleConst(n)
private fun cz(b: Boolean): MirExpr = MirExpr.BooleanConst(b)
private fun cc(n: Int): MirExpr = MirExpr.CharConst(n)
private fun cs(s: String): MirExpr = MirExpr.StringConst(s)
private fun lv(x: MirLocal): MirExpr = MirExpr.Local(x)
private fun bin(op: BinaryOp, a: MirExpr, b: MirExpr): MirExpr = MirExpr.Binary(op, a, b)
private fun pr(e: MirExpr): MirStmt {
    val name = when (e.type) {
        TInt -> "mk_println_int"
        TLong -> "mk_println_long"
        TDouble -> "mk_println_double"
        TBoolean -> "mk_println_boolean"
        TChar -> "mk_println_char"
        else -> "mk_println_string"
    }
    return MirStmt.Eval(MirExpr.RuntimeCall(name, listOf(e), TUnit))
}

// ---- 1. handcrafted modules ---------------------------------------------------------------------------------

private fun demoModule(): MirModule {
    val n = MirLocal(0, "n", TInt)
    val acc = MirLocal(1, "acc", TInt)
    val i = MirLocal(2, "i", TInt)
    val label = MirLabel(0)
    val sum = MirFunction(
        "demo.sum", Mangling.function("demo", "sum", listOf(TInt)), listOf(n), TInt,
        listOf(
            MirStmt.Declare(acc, ci(0)),
            MirStmt.ForRange(
                label, i, ci(1), lv(n), null, true, true,
                listOf(MirStmt.Assign(acc, bin(BinaryOp.Add, lv(acc), lv(i)))),
            ),
            MirStmt.Return(lv(acc)),
        ),
    )
    val r = MirLocal(3, "r", TInt)
    val s = MirLocal(4, "s", TString)
    val main = MirFunction(
        "main", Mangling.function("", "main", emptyList()), emptyList(), TUnit,
        listOf(
            MirStmt.Declare(r, MirExpr.Call(sum.cName, listOf(ci(10)), TInt)),
            MirStmt.Declare(s, cs("sum = \"x\"\n")),
            MirStmt.If(bin(BinaryOp.Gt, lv(r), ci(50)), listOf(pr(lv(s))), listOf(pr(lv(r)))),
            pr(bin(BinaryOp.Div, lv(r), ci(0))),
        ),
    )
    return MirModule("demo.kt", listOf(sum, main))
}

/** Every operator on every type it applies to, all conversions, all constants (including the awkward ones). */
private fun arithmeticModule(): MirModule {
    val body = ArrayList<MirStmt>()
    var id = 0
    fun decl(name: String, e: MirExpr): MirLocal {
        val l = MirLocal(id++, name, e.type)
        body += MirStmt.Declare(l, e)
        return l
    }
    val ints = listOf(ci(0), ci(1), ci(-1), ci(Int.MAX_VALUE), ci(Int.MIN_VALUE), ci(1234567))
    val longs = listOf(cl(0), cl(7), cl(-9), cl(Long.MAX_VALUE), cl(Long.MIN_VALUE), cl(4294967296L))
    val doubles = listOf(cd(0.0), cd(-0.0), cd(1.5), cd(-2.25), cd(0.1), cd(1.0e21), cd(1.0e-7), cd(123456789.125), cd(Double.NaN),
        cd(Double.POSITIVE_INFINITY), cd(Double.NEGATIVE_INFINITY), cd(Double.MAX_VALUE), cd(Double.MIN_VALUE), cd(100.0), cd(3.141592653589793))
    val ops = listOf(BinaryOp.Add, BinaryOp.Sub, BinaryOp.Mul, BinaryOp.Div, BinaryOp.Rem, BinaryOp.And, BinaryOp.Or, BinaryOp.Xor)
    for (op in ops) {
        decl("i_$op", bin(op, ints[3], ints[1]))
        decl("l_$op", bin(op, longs[4], longs[2]))
        if (op.ordinal < 5) decl("d_$op", bin(op, doubles[2], doubles[3]))
    }
    for (op in listOf(BinaryOp.Shl, BinaryOp.Shr, BinaryOp.Ushr)) {
        decl("si_$op", bin(op, ints[5], ci(35)))
        decl("sl_$op", bin(op, longs[5], ci(70)))
    }
    for (op in listOf(BinaryOp.Eq, BinaryOp.Ne, BinaryOp.Lt, BinaryOp.Le, BinaryOp.Gt, BinaryOp.Ge)) {
        decl("ci_$op", bin(op, ints[0], ints[1]))
        decl("cl_$op", bin(op, longs[0], longs[1]))
        decl("cd_$op", bin(op, doubles[0], doubles[1]))
        decl("cc_$op", bin(op, cc(65), cc(97)))
    }
    decl("b_and", bin(BinaryOp.And, cz(true), cz(false)))
    decl("b_or", bin(BinaryOp.Or, cz(true), cz(false)))
    decl("b_xor", bin(BinaryOp.Xor, cz(true), cz(false)))
    decl("b_andand", bin(BinaryOp.AndAnd, cz(true), cz(false)))
    decl("b_oror", bin(BinaryOp.OrOr, cz(true), cz(false)))
    for (u in listOf(UnaryOp.Neg, UnaryOp.Inv)) {
        decl("ui_$u", MirExpr.Unary(u, ints[4]))
        decl("ul_$u", MirExpr.Unary(u, longs[4]))
    }
    decl("ud", MirExpr.Unary(UnaryOp.Neg, doubles[2]))
    decl("un", MirExpr.Unary(UnaryOp.Not, cz(false)))
    for (d in doubles) {
        decl("d2i", MirExpr.Convert(d, TInt))
        decl("d2l", MirExpr.Convert(d, TLong))
        decl("d2c", MirExpr.Convert(d, TChar))
        decl("dd", d)
    }
    decl("i2l", MirExpr.Convert(ints[3], TLong))
    decl("i2d", MirExpr.Convert(ints[3], TDouble))
    decl("i2c", MirExpr.Convert(ints[5], TChar))
    decl("l2i", MirExpr.Convert(longs[3], TInt))
    decl("l2d", MirExpr.Convert(longs[3], TDouble))
    decl("l2c", MirExpr.Convert(longs[5], TChar))
    decl("c2i", MirExpr.Convert(cc(0x20AC), TInt))
    decl("c2l", MirExpr.Convert(cc(0xFFFF), TLong))
    decl("c2d", MirExpr.Convert(cc(65), TDouble))
    for (i in ints) decl("ic", i)
    for (l in longs) decl("lc", l)
    decl("cond", MirExpr.Conditional(cz(true), ci(1), ci(2)))
    decl("condd", MirExpr.Conditional(bin(BinaryOp.Lt, cd(1.0), cd(2.0)), cd(1.0), cd(2.0), TDouble))
    // strings: escapes, non-ASCII, a literal that appears twice (deduplicated), length, equality
    val s1 = decl("s1", cs("plain"))
    val s2 = decl("s2", cs("quote\" back\\ nl\n tab\t nul\u0000 euro€ emoji😀 ?? ??= */ /*"))
    decl("s3", cs("plain"))
    decl("sl", MirExpr.StringLength(lv(s2)))
    decl("seq", bin(BinaryOp.Eq, lv(s1), lv(s2)))
    decl("sne", bin(BinaryOp.Ne, lv(s1), lv(s2)))
    decl("sid", bin(BinaryOp.IdEq, lv(s1), lv(s2)))
    decl("snid", bin(BinaryOp.IdNe, lv(s1), MirExpr.NullConst(TNull)))
    val cat = MirLocal(id++, "cat", TString)
    body += MirStmt.Declare(cat, MirExpr.RuntimeCall("mk_string_concat", listOf(lv(s1), lv(s2)), TString))
    body += MirStmt.Assign(cat, MirExpr.RuntimeCall("mk_string_concat", listOf(lv(cat), cs("!")), TString))
    body += MirStmt.Eval(MirExpr.RuntimeCall("mk_string_concat", listOf(lv(cat), lv(s1)), TString))
    body += MirStmt.Return(null)
    val main = MirFunction("arith.main", Mangling.function("arith", "main", emptyList()), emptyList(), TUnit, body)
    return MirModule("arith.kt", listOf(main))
}

/** All loop forms with break/continue, ranges of every progression kind, nested scopes releasing references. */
private fun loopsModule(): MirModule {
    var id = 0
    var lab = 0
    fun loc(name: String, t: MirType) = MirLocal(id++, name, t)
    val body = ArrayList<MirStmt>()
    // ranges: ascending/descending, inclusive/exclusive, with and without step, Int/Long/Char
    for (asc in listOf(true, false)) for (incl in listOf(true, false)) for (stepKind in 0..2) {
        for (t in listOf<MirType>(TInt, TLong, TChar)) {
            val v = loc("v", t)
            val (a, b) = when (t) {
                TInt -> ci(if (asc) 1 else 10) to ci(if (asc) 10 else 1)
                TLong -> cl(if (asc) 1 else 10) to cl(if (asc) 10 else 1)
                else -> cc(if (asc) 'a'.code else 'k'.code) to cc(if (asc) 'k'.code else 'a'.code)
            }
            val step: MirExpr? = when (stepKind) {
                0 -> null
                1 -> if (t == TLong) cl(3) else ci(3)
                else -> if (t == TLong) MirExpr.Convert(ci(2), TLong) else bin(BinaryOp.Add, ci(1), ci(1))
            }
            val inner = ArrayList<MirStmt>()
            inner += pr(MirExpr.Convert(lv(v), if (t == TLong) TLong else TInt))
            body += MirStmt.ForRange(MirLabel(lab++), v, a, b, step, asc, incl, inner)
        }
    }
    // while / loop / do-while with labelled break and continue, a reference local per iteration
    val outer = MirLabel(lab++)
    val inner = MirLabel(lab++)
    val i = loc("i", TInt)
    val s = loc("s", TString)
    val t = loc("t", TString)
    val j = loc("j", TInt)
    body += MirStmt.Declare(i, ci(0))
    body += MirStmt.While(
        outer, bin(BinaryOp.Lt, lv(i), ci(10)),
        listOf(
            MirStmt.Declare(s, MirExpr.RuntimeCall("mk_string_concat", listOf(cs("a"), cs("b")), TString)),
            MirStmt.Assign(i, bin(BinaryOp.Add, lv(i), ci(1))),
            MirStmt.If(bin(BinaryOp.Eq, lv(i), ci(3)), listOf(MirStmt.Continue(outer)), emptyList()),
            MirStmt.Declare(j, ci(0)),
            MirStmt.Loop(
                inner,
                listOf(
                    MirStmt.Declare(t, MirExpr.RuntimeCall("mk_string_concat", listOf(lv(s), cs("c")), TString)),
                    MirStmt.Assign(j, bin(BinaryOp.Add, lv(j), ci(1))),
                    MirStmt.If(bin(BinaryOp.Gt, lv(j), ci(2)), listOf(MirStmt.Break(inner)), emptyList()),
                    MirStmt.If(bin(BinaryOp.Gt, lv(i), ci(8)), listOf(MirStmt.Break(outer)), emptyList()),
                    pr(lv(t)),
                    MirStmt.Continue(inner),
                ),
            ),
        ),
    )
    val dw = MirLabel(lab++)
    val k = loc("k", TInt)
    val u = loc("u", TString)
    val flag = loc("flag", TBoolean)
    body += MirStmt.Declare(k, ci(0))
    body += MirStmt.DoWhile(
        dw,
        listOf(
            MirStmt.Declare(u, MirExpr.RuntimeCall("mk_string_concat", listOf(cs("x"), cs("y")), TString)),
            MirStmt.Assign(k, bin(BinaryOp.Add, lv(k), ci(1))),
            MirStmt.If(bin(BinaryOp.Eq, lv(k), ci(2)), listOf(MirStmt.Continue(dw)), emptyList()),
            MirStmt.If(bin(BinaryOp.Eq, lv(k), ci(7)), listOf(MirStmt.Break(dw)), emptyList()),
        ),
        listOf(MirStmt.Declare(flag, bin(BinaryOp.Lt, lv(k), ci(5)))),
        bin(BinaryOp.AndAnd, lv(flag), bin(BinaryOp.Ne, MirExpr.StringLength(lv(u)), ci(0))),
    )
    body += MirStmt.Block(listOf(MirStmt.Declare(loc("blk", TString), cs("block")), MirStmt.Panic("never reached")))
    body += MirStmt.Return(null)
    val main = MirFunction("loops.main", Mangling.function("loops", "main", emptyList()), emptyList(), TUnit, body)
    // a function that returns a reference local (moved out), one that returns a parameter (retained)
    val p = MirLocal(100, "p", TString)
    val r = MirLocal(101, "r", TString)
    val f1 = MirFunction(
        "loops.pass", Mangling.function("loops", "pass", listOf(TString)), listOf(p), TString,
        listOf(MirStmt.Return(lv(p))),
    )
    val f2 = MirFunction(
        "loops.make", Mangling.function("loops", "make", listOf(TString)), listOf(p), TString,
        listOf(
            MirStmt.Declare(r, MirExpr.RuntimeCall("mk_string_concat", listOf(lv(p), cs("!")), TString)),
            MirStmt.If(bin(BinaryOp.Eq, lv(r), lv(p)), listOf(MirStmt.Return(cs("same"))), emptyList()),
            MirStmt.Return(lv(r)),
        ),
    )
    val f3 = MirFunction("loops.noReturn", Mangling.function("loops", "noReturn", emptyList()), emptyList(), TInt, listOf(MirStmt.Eval(ci(1))))
    return MirModule("loops.kt", listOf(main, f1, f2, f3))
}

private val shapeI = TObject("shapes.Shape", "kc_shapes_DShape", false, true)
private val baseT = TObject("shapes.Base", "kc_shapes_DBase", false)
private val baseN = TObject("shapes.Base", "kc_shapes_DBase", true)
private val circleT = TObject("shapes.Circle", "kc_shapes_DCircle", false)
private val registryT = TObject("shapes.Registry", "kc_shapes_DRegistry", false)
private val iseT = TObject("kotlin.IllegalStateException", "MkIllegalStateException", false)
private val exT = TObject("kotlin.Exception", "MkException", false)

/** Classes, interfaces, objects, virtual and interface calls, casts, boxing, exceptions. */
private fun classesModule(): MirModule {
    val shape = MirClass("shapes.Shape", shapeI.cName, true, null, emptyList(), emptyList(), emptyList(), emptyList())
    val describeBase = Mangling.method(baseT.cName, "describe", emptyList())
    val describeCircle = Mangling.method(circleT.cName, "describe", emptyList())
    val areaCircle = Mangling.method(circleT.cName, "area", emptyList())
    val base = MirClass(
        "shapes.Base", baseT.cName, false, null,
        listOf(MirField("x", TInt), MirField("label", TString)), emptyList(),
        listOf(null, null, null, describeBase), emptyList(),
    )
    val circle = MirClass(
        "shapes.Circle", circleT.cName, false, baseT.cName,
        listOf(MirField("r", TDouble), MirField("next", baseN), MirField("boxed", TBoxed(TInt))), listOf(shapeI.cName),
        listOf(null, null, null, describeCircle), listOf(MirItable(shapeI.cName, listOf(areaCircle))),
    )
    val ctorBaseName = Mangling.constructor(baseT.cName, listOf(TInt, TString))
    val ctorCircleName = Mangling.constructor(circleT.cName, listOf(TInt, TString, TDouble))
    val ctorRegName = Mangling.constructor(registryT.cName, emptyList())
    val registry = MirClass(
        "shapes.Registry", registryT.cName, false, null, listOf(MirField("count", TInt)), emptyList(),
        listOf(null, null, null), emptyList(), objectCtor = ctorRegName,
    )
    val iseClass = MirClass("kotlin.IllegalStateException", iseT.cName, false, "MkException", emptyList(), emptyList(), emptyList(), emptyList(), external = true)
    val exClass = MirClass("kotlin.Exception", exT.cName, false, null, emptyList(), emptyList(), emptyList(), emptyList(), external = true)

    val fns = ArrayList<MirFunction>()
    run {
        val self = MirLocal(0, "this", baseT)
        val x = MirLocal(1, "x", TInt)
        val label = MirLocal(2, "label", TString)
        fns += MirFunction(
            "shapes.Base.<init>", ctorBaseName, listOf(self, x, label), TUnit,
            listOf(
                MirStmt.SetField(lv(self), baseT.cName, "x", TInt, lv(x)),
                MirStmt.SetField(lv(self), baseT.cName, "label", TString, lv(label)),
            ),
            constructorOf = baseT.cName,
        )
    }
    run {
        val self = MirLocal(0, "this", circleT)
        val x = MirLocal(1, "x", TInt)
        val label = MirLocal(2, "label", TString)
        val r = MirLocal(3, "r", TDouble)
        fns += MirFunction(
            "shapes.Circle.<init>", ctorCircleName, listOf(self, x, label, r), TUnit,
            listOf(
                MirStmt.Eval(MirExpr.Call(ctorBaseName, listOf(lv(self), lv(x), lv(label)), TUnit)),
                MirStmt.SetField(lv(self), circleT.cName, "r", TDouble, lv(r)),
                MirStmt.SetField(lv(self), circleT.cName, "next", baseN, MirExpr.NullConst(baseN)),
                MirStmt.SetField(lv(self), circleT.cName, "boxed", TBoxed(TInt), MirExpr.Box(ci(5), TBoxed(TInt))),
            ),
            constructorOf = circleT.cName,
        )
    }
    run {
        val self = MirLocal(0, "this", registryT)
        fns += MirFunction(
            "shapes.Registry.<init>", ctorRegName, listOf(self), TUnit,
            listOf(MirStmt.SetField(lv(self), registryT.cName, "count", TInt, ci(0))), constructorOf = registryT.cName,
        )
    }
    run {
        val self = MirLocal(0, "this", baseT)
        fns += MirFunction("shapes.Base.describe", describeBase, listOf(self), TString, listOf(MirStmt.Return(MirExpr.FieldGet(lv(self), baseT.cName, "label", TString))))
    }
    run {
        val self = MirLocal(0, "this", circleT)
        val t = MirLocal(1, "t", TString)
        fns += MirFunction(
            "shapes.Circle.describe", describeCircle, listOf(self), TString,
            listOf(
                MirStmt.Declare(t, MirExpr.RuntimeCall("mk_string_concat", listOf(cs("circle "), MirExpr.FieldGet(lv(self), baseT.cName, "label", TString)), TString)),
                MirStmt.Return(lv(t)),
            ),
        )
        fns += MirFunction(
            "shapes.Circle.area", areaCircle, listOf(self), TDouble,
            listOf(
                MirStmt.Return(
                    bin(BinaryOp.Mul, cd(3.14159), bin(BinaryOp.Mul, MirExpr.FieldGet(lv(self), circleT.cName, "r", TDouble), MirExpr.FieldGet(lv(self), circleT.cName, "r", TDouble))),
                ),
            ),
        )
    }
    // the driver function
    var id = 0
    fun loc(name: String, t: MirType) = MirLocal(id++, name, t)
    val b = loc("b", baseT)
    val c = loc("c", circleT)
    val d = loc("d", baseT)
    val sh = loc("sh", shapeI)
    val s = loc("s", TString)
    val bx = loc("bx", TBoxed(TInt))
    val n = loc("n", TInt)
    val reg = loc("reg", registryT)
    val maybe = loc("maybe", baseN)
    val ok = loc("ok", TBoolean)
    val ex = loc("ex", exT)
    val ise = loc("ise", iseT)
    val area = loc("area", TDouble)
    val safe = loc("safe", circleT.asNullable())
    val e1 = loc("e1", iseT)
    val body = listOf(
        MirStmt.Declare(b, MirExpr.New(ctorBaseName, listOf(ci(1), cs("base")), baseT)),
        MirStmt.Declare(c, MirExpr.New(ctorCircleName, listOf(ci(2), cs("c"), cd(2.0)), circleT)),
        MirStmt.Declare(d, MirExpr.Cast(lv(c), baseT, MirExpr.CastMode.Unchecked)),
        MirStmt.Declare(sh, MirExpr.Cast(lv(c), shapeI, MirExpr.CastMode.Unchecked)),
        MirStmt.Declare(s, MirExpr.VCall(3, lv(d), emptyList(), emptyList(), TString)),
        pr(lv(s)),
        MirStmt.Declare(area, MirExpr.ICall(shapeI.cName, 0, lv(sh), emptyList(), emptyList(), TDouble)),
        pr(lv(area)),
        MirStmt.Declare(bx, MirExpr.Box(ci(41), TBoxed(TInt))),
        MirStmt.Declare(n, bin(BinaryOp.Add, MirExpr.Unbox(lv(bx), TInt), ci(1))),
        pr(lv(n)),
        MirStmt.Declare(reg, MirExpr.ObjectGet(registryT.cName, registryT)),
        MirStmt.SetField(lv(reg), registryT.cName, "count", TInt, bin(BinaryOp.Add, MirExpr.FieldGet(lv(reg), registryT.cName, "count", TInt), ci(1))),
        MirStmt.SetField(lv(c), circleT.cName, "next", baseN, lv(b)),
        MirStmt.Declare(maybe, MirExpr.FieldGet(lv(c), circleT.cName, "next", baseN)),
        MirStmt.Declare(ok, MirExpr.IsInstance(lv(maybe), circleT)),
        MirStmt.If(lv(ok), listOf(pr(cs("circle"))), listOf(pr(cs("not a circle")))),
        MirStmt.Declare(safe, MirExpr.Cast(lv(maybe), circleT.asNullable(), MirExpr.CastMode.Safe)),
        MirStmt.Eval(MirExpr.Cast(lv(d), circleT, MirExpr.CastMode.Checked)),
        MirStmt.Eval(MirExpr.NotNull(lv(maybe))),
        MirStmt.Eval(MirExpr.Cast(lv(bx), TBoxed(TInt), MirExpr.CastMode.Checked)),
        MirStmt.Eval(MirExpr.IsInstance(lv(bx), TBoxed(TInt))),
        MirStmt.Eval(MirExpr.IsInstance(cs("x"), TString)),
        MirStmt.Eval(MirExpr.RuntimeCall("mk_equals", listOf(lv(bx), lv(maybe)), TBoolean)),
        MirStmt.Eval(MirExpr.TypeInfoOf(circleT)),
        MirStmt.Eval(MirExpr.TypeInfoOf(null)),
        MirStmt.Try(
            listOf(
                MirStmt.Declare(ise, MirExpr.RuntimeCall("mk_exception_new", listOf(cs("boom")), iseT)),
                MirStmt.Throw(lv(ise)),
            ),
            listOf(
                MirCatch(iseT, listOf(MirStmt.Declare(e1, MirExpr.CaughtException(iseT)), pr(cs("caught ise")))),
                MirCatch(exT, listOf(MirStmt.Declare(ex, MirExpr.CaughtException(exT)), pr(cs("caught ex")), MirStmt.Throw(lv(ex)))),
            ),
        ),
        MirStmt.Return(null),
    )
    fns += MirFunction("shapes.main", Mangling.function("shapes", "main", emptyList()), emptyList(), TUnit, body)
    return MirModule("shapes.kt", fns, listOf(shape, base, circle, registry, exClass, iseClass))
}

// ---- 2. random MIR ------------------------------------------------------------------------------------------

private class FnSig(val cName: String, val params: List<MirType>, val ret: MirType)

private class MirGen(private val rng: Rng) {
    private val scalarTypes = listOf<MirType>(TInt, TLong, TDouble, TBoolean, TChar)
    private val allTypes = scalarTypes + TString
    private var nextLocal = 0
    private var nextLabel = 0
    private val scopes = ArrayList<ArrayList<MirLocal>>()
    private val loops = ArrayList<MirLabel>()
    private val earlier = ArrayList<FnSig>()
    private val strings = listOf("", "a", "hello", "tab\there", "q\"uote", "unié中", "back\\slash", "line\nbreak", "\u0001ctl")

    private fun fresh(name: String, t: MirType): MirLocal = MirLocal(nextLocal++, name, t)

    private fun visible(t: MirType): List<MirLocal> = scopes.flatten().filter { it.type == t }

    private fun intConst(): MirExpr = when (rng.below(6)) {
        0 -> ci(0)
        1 -> ci(1)
        2 -> ci(-1)
        3 -> ci(Int.MIN_VALUE)
        4 -> ci(Int.MAX_VALUE)
        else -> ci(rng.next() - 1000000000)
    }

    private fun longConst(): MirExpr = when (rng.below(5)) {
        0 -> cl(0)
        1 -> cl(Long.MIN_VALUE)
        2 -> cl(Long.MAX_VALUE)
        3 -> cl(-1L)
        else -> cl(rng.next().toLong() * 4096L - 987654321L)
    }

    private fun doubleConst(): MirExpr = when (rng.below(7)) {
        0 -> cd(0.0)
        1 -> cd(-0.5)
        2 -> cd(Double.NaN)
        3 -> cd(rng.next().toDouble() / 7.0)
        4 -> cd(rng.next().toDouble() * 1.0e15)
        5 -> cd(1.0 / (rng.next() + 1).toDouble())
        else -> cd(Double.NEGATIVE_INFINITY)
    }

    fun expr(t: MirType, depth: Int): MirExpr {
        val locals = visible(t)
        if (depth <= 0 || rng.chance(15)) {
            if (locals.isNotEmpty() && rng.chance(60)) return lv(rng.pick(locals))
            return constOf(t)
        }
        return when (t) {
            TInt -> intExpr(depth)
            TLong -> longExpr(depth)
            TDouble -> doubleExpr(depth)
            TBoolean -> boolExpr(depth)
            TChar -> charExpr(depth)
            else -> if (locals.isNotEmpty() && rng.chance(70)) lv(rng.pick(locals)) else cs(rng.pick(strings))
        }
    }

    private fun constOf(t: MirType): MirExpr = when (t) {
        TInt -> intConst()
        TLong -> longConst()
        TDouble -> doubleConst()
        TBoolean -> cz(rng.chance(50))
        TChar -> cc(rng.pick(listOf(0, 65, 0x7F, 0xE9, 0x20AC, 0xFFFF)))
        else -> cs(rng.pick(strings))
    }

    private fun callOf(t: MirType, depth: Int): MirExpr? {
        val cands = earlier.filter { it.ret == t }
        if (cands.isEmpty()) return null
        val f = rng.pick(cands)
        return MirExpr.Call(f.cName, f.params.map { expr(it, depth - 1) }, t)
    }

    private fun intExpr(depth: Int): MirExpr = when (rng.below(9)) {
        0, 1, 2 -> bin(rng.pick(listOf(BinaryOp.Add, BinaryOp.Sub, BinaryOp.Mul, BinaryOp.Div, BinaryOp.Rem, BinaryOp.And, BinaryOp.Or, BinaryOp.Xor)), expr(TInt, depth - 1), expr(TInt, depth - 1))
        3 -> bin(rng.pick(listOf(BinaryOp.Shl, BinaryOp.Shr, BinaryOp.Ushr)), expr(TInt, depth - 1), expr(TInt, depth - 1))
        4 -> MirExpr.Unary(rng.pick(listOf(UnaryOp.Neg, UnaryOp.Inv)), expr(TInt, depth - 1))
        5 -> MirExpr.Convert(expr(rng.pick(listOf<MirType>(TLong, TChar, TDouble)), depth - 1), TInt)
        6 -> MirExpr.Conditional(expr(TBoolean, depth - 1), expr(TInt, depth - 1), expr(TInt, depth - 1), TInt)
        7 -> MirExpr.StringLength(expr(TString, depth - 1))
        else -> callOf(TInt, depth) ?: intConst()
    }

    private fun longExpr(depth: Int): MirExpr = when (rng.below(7)) {
        0, 1, 2 -> bin(rng.pick(listOf(BinaryOp.Add, BinaryOp.Sub, BinaryOp.Mul, BinaryOp.Div, BinaryOp.Rem, BinaryOp.And, BinaryOp.Or, BinaryOp.Xor)), expr(TLong, depth - 1), expr(TLong, depth - 1))
        3 -> bin(rng.pick(listOf(BinaryOp.Shl, BinaryOp.Shr, BinaryOp.Ushr)), expr(TLong, depth - 1), expr(TInt, depth - 1))
        4 -> MirExpr.Unary(rng.pick(listOf(UnaryOp.Neg, UnaryOp.Inv)), expr(TLong, depth - 1))
        5 -> MirExpr.Convert(expr(rng.pick(listOf<MirType>(TInt, TChar, TDouble)), depth - 1), TLong)
        else -> callOf(TLong, depth) ?: longConst()
    }

    private fun doubleExpr(depth: Int): MirExpr = when (rng.below(6)) {
        0, 1, 2 -> bin(rng.pick(listOf(BinaryOp.Add, BinaryOp.Sub, BinaryOp.Mul, BinaryOp.Div, BinaryOp.Rem)), expr(TDouble, depth - 1), expr(TDouble, depth - 1))
        3 -> MirExpr.Unary(UnaryOp.Neg, expr(TDouble, depth - 1))
        4 -> MirExpr.Convert(expr(rng.pick(listOf<MirType>(TInt, TLong, TChar)), depth - 1), TDouble)
        else -> callOf(TDouble, depth) ?: doubleConst()
    }

    private fun charExpr(depth: Int): MirExpr = when (rng.below(3)) {
        0 -> MirExpr.Convert(expr(rng.pick(listOf<MirType>(TInt, TLong, TDouble)), depth - 1), TChar)
        1 -> MirExpr.Conditional(expr(TBoolean, depth - 1), expr(TChar, depth - 1), expr(TChar, depth - 1), TChar)
        else -> callOf(TChar, depth) ?: constOf(TChar)
    }

    private fun boolExpr(depth: Int): MirExpr = when (rng.below(8)) {
        0, 1, 2 -> {
            val t = rng.pick(listOf<MirType>(TInt, TLong, TDouble, TChar))
            bin(rng.pick(listOf(BinaryOp.Eq, BinaryOp.Ne, BinaryOp.Lt, BinaryOp.Le, BinaryOp.Gt, BinaryOp.Ge)), expr(t, depth - 1), expr(t, depth - 1))
        }
        3 -> bin(rng.pick(listOf(BinaryOp.AndAnd, BinaryOp.OrOr, BinaryOp.And, BinaryOp.Or, BinaryOp.Xor)), expr(TBoolean, depth - 1), expr(TBoolean, depth - 1))
        4 -> MirExpr.Unary(UnaryOp.Not, expr(TBoolean, depth - 1))
        5 -> bin(rng.pick(listOf(BinaryOp.Eq, BinaryOp.Ne, BinaryOp.IdEq, BinaryOp.IdNe)), expr(TString, depth - 1), expr(TString, depth - 1))
        6 -> MirExpr.Conditional(expr(TBoolean, depth - 1), expr(TBoolean, depth - 1), expr(TBoolean, depth - 1), TBoolean)
        else -> callOf(TBoolean, depth) ?: cz(rng.chance(50))
    }

    // ---- statements ----

    private fun block(depth: Int, count: Int): List<MirStmt> {
        scopes += ArrayList<MirLocal>()
        val out = ArrayList<MirStmt>()
        repeat(count) { stmt(depth, out) }
        scopes.removeAt(scopes.size - 1)
        return out
    }

    private fun declare(t: MirType, out: MutableList<MirStmt>, depth: Int) {
        val l = fresh("v", t)
        val init: MirExpr = if (t == TString && rng.chance(40)) {
            // an owned string: only legal as the initialiser of a declaration
            MirExpr.RuntimeCall("mk_string_concat", listOf(expr(TString, 1), expr(TString, 1)), TString)
        } else expr(t, depth)
        out += MirStmt.Declare(l, init)
        scopes.last() += l
    }

    private fun stmt(depth: Int, out: MutableList<MirStmt>) {
        val k = if (depth <= 0) rng.below(4) else rng.below(16)
        when (k) {
            0, 1 -> declare(rng.pick(allTypes), out, 2)
            2 -> {
                val t = rng.pick(allTypes)
                val vs = visible(t)
                if (vs.isEmpty()) declare(t, out, 2)
                else {
                    val target = rng.pick(vs)
                    val v: MirExpr = if (t == TString && rng.chance(40)) MirExpr.RuntimeCall("mk_string_concat", listOf(expr(TString, 1), cs("+")), TString) else expr(t, 2)
                    out += MirStmt.Assign(target, v)
                }
            }
            3 -> out += pr(expr(rng.pick(allTypes), 2))
            4, 5 -> out += MirStmt.If(expr(TBoolean, 2), block(depth - 1, 1 + rng.below(3)), if (rng.chance(50)) block(depth - 1, 1 + rng.below(2)) else emptyList())
            6 -> {
                val label = MirLabel(nextLabel++)
                loops += label
                val body = block(depth - 1, 1 + rng.below(3)) + loopExit(label)
                loops.removeAt(loops.size - 1)
                out += MirStmt.While(label, expr(TBoolean, 2), body)
            }
            7 -> {
                val label = MirLabel(nextLabel++)
                loops += label
                val body = block(depth - 1, 1 + rng.below(3)) + loopExit(label)
                loops.removeAt(loops.size - 1)
                out += MirStmt.Loop(label, body + MirStmt.Break(label))
            }
            8 -> {
                val label = MirLabel(nextLabel++)
                loops += label
                scopes += ArrayList<MirLocal>()
                val body = ArrayList<MirStmt>()
                repeat(1 + rng.below(3)) { stmt(depth - 1, body) }
                body += loopExit(label)
                val condStmts = ArrayList<MirStmt>()
                declare(TBoolean, condStmts, 2)
                val cond = lv(scopes.last().last())
                scopes.removeAt(scopes.size - 1)
                loops.removeAt(loops.size - 1)
                out += MirStmt.DoWhile(label, body, condStmts, cond)
            }
            9, 10 -> {
                val label = MirLabel(nextLabel++)
                val t = rng.pick(listOf<MirType>(TInt, TLong, TChar))
                val v = fresh("it", t)
                val step: MirExpr? = if (rng.chance(50)) (if (t == TLong) cl(1L + rng.below(4)) else ci(1 + rng.below(4))) else null
                val start = expr(t, 1)
                val end = expr(t, 1)
                loops += label
                scopes += arrayListOf(v)
                val body = ArrayList<MirStmt>()
                repeat(1 + rng.below(3)) { stmt(depth - 1, body) }
                if (rng.chance(30)) body += loopExit(label)
                scopes.removeAt(scopes.size - 1)
                loops.removeAt(loops.size - 1)
                out += MirStmt.ForRange(label, v, start, end, step, rng.chance(60), rng.chance(50), body)
            }
            11 -> out += MirStmt.Block(block(depth - 1, 1 + rng.below(3)))
            12 -> if (rng.chance(20)) out += MirStmt.If(expr(TBoolean, 1), listOf(MirStmt.Panic("generated panic " + rng.below(100))), emptyList()) else declare(TString, out, 2)
            13 -> {
                // try / catch with a thrown exception object
                val ex = fresh("ex", iseT)
                val caught = fresh("caught", iseT)
                val body = ArrayList<MirStmt>()
                body += MirStmt.Declare(ex, MirExpr.RuntimeCall("mk_exception_new", listOf(cs("generated")), iseT))
                scopes += arrayListOf(ex)
                repeat(rng.below(3)) { stmt(depth - 1, body) }
                body += MirStmt.If(expr(TBoolean, 1), listOf(MirStmt.Throw(lv(ex))), emptyList())
                scopes.removeAt(scopes.size - 1)
                val handler = ArrayList<MirStmt>()
                handler += MirStmt.Declare(caught, MirExpr.CaughtException(iseT))
                handler += pr(cs("handled"))
                out += MirStmt.Try(body, listOf(MirCatch(iseT, handler)))
            }
            14 -> {
                val f = if (earlier.isEmpty()) null else rng.pick(earlier)
                if (f != null && f.ret == TUnit) out += MirStmt.Eval(MirExpr.Call(f.cName, f.params.map { expr(it, 1) }, TUnit))
                else declare(rng.pick(scalarTypes), out, 3)
            }
            else -> declare(rng.pick(scalarTypes), out, 3)
        }
    }

    private fun loopExit(label: MirLabel): MirStmt =
        MirStmt.If(
            expr(TBoolean, 1),
            listOf(if (loops.size > 1 && rng.chance(30)) MirStmt.Break(loops[loops.size - 2]) else if (rng.chance(50)) MirStmt.Break(label) else MirStmt.Continue(label)),
            emptyList(),
        )

    fun function(index: Int): MirFunction {
        nextLocal = 0
        scopes.clear()
        loops.clear()
        val params = ArrayList<MirLocal>()
        repeat(rng.below(4)) { params += fresh("p", rng.pick(allTypes)) }
        val ret: MirType = if (rng.chance(25)) TUnit else rng.pick(allTypes)
        scopes += ArrayList(params)
        val body = ArrayList<MirStmt>()
        repeat(2 + rng.below(5)) { stmt(3, body) }
        when (ret) {
            TUnit -> if (rng.chance(50)) body += MirStmt.Return(null)
            else -> body += MirStmt.Return(expr(ret, 2))
        }
        scopes.clear()
        val cName = Mangling.function("gen", "f$index", params.map { it.type })
        val f = MirFunction("gen.f$index", cName, params, ret, body)
        earlier += FnSig(cName, params.map { it.type }, ret)
        return f
    }

    fun reset() {
        earlier.clear()
    }
}

// ---- 3. memory checker programs -----------------------------------------------------------------------------

private const val HEAP = "mini.memory.Heap"

private class MemEnv {
    val cls = MClass("gen.Res", MemMode.Strict, HEAP, false)
    val resT = MType(TK.Strict, cls = cls, className = "gen.Res", display = "Res")
    val resN = MType(TK.Strict, nullable = true, cls = cls, className = "gen.Res", display = "Res?")
    val arenaT = MType(TK.Alloc, alloc = AllocKind.Arena, className = "mini.memory.Arena", display = "Arena")
    val heapT = MType(TK.Alloc, alloc = AllocKind.Heap, className = HEAP, display = "Heap")
    val plainCls = MClass("gen.Plain", MemMode.Strict, null, false)
    val plainT = MType(TK.Strict, cls = plainCls, className = "gen.Plain", display = "Plain")
    val managedCls = MClass("gen.Managed", MemMode.Managed, null, false)
    val managedT = MType(TK.Managed, cls = managedCls, className = "gen.Managed", display = "Managed")
    var localIds = 100

    val nextField = MField("next", resN, false, cls)
    val peerField = MField("peer", resT, true, cls)

    init {
        cls.fields += nextField
        cls.fields += peerField
        plainCls.fields += MField("v", MType.PRIM, false, plainCls)
    }

    val ctor = MFun("gen.Res.<init>", MemMode.Strict, emptyList(), MReturn(resT), ctorOf = cls)
    val plainCtor = MFun("gen.Plain.<init>", MemMode.Strict, emptyList(), MReturn(plainT), ctorOf = plainCls)
    val arenaCtor = MFun("mini.memory.Arena.<init>", MemMode.Managed, emptyList(), MReturn(arenaT), external = true)

    fun local(name: String, t: MType, pos: Pos) = MLocal(localIds++, name, t, pos)

    val consume = MFun(
        "gen.consume", MemMode.Strict, listOf(MParam(MLocal(1, "r", resT), MAnnot(owned = Pos(1, 1)))), MReturn(MType.UNIT),
    ).also { it.body = listOf(MStmt.Eval(MExpr.Free(MExpr.Local(it.params[0].local, Pos(2, 5)), null, FreeKind.Free, Pos(2, 5)), Pos(2, 5))) }
    val borrow = MFun("gen.borrow", MemMode.Strict, listOf(MParam(MLocal(1, "r", resT))), MReturn(MType.UNIT)).also { it.body = emptyList() }
    val make = MFun("gen.make", MemMode.Strict, emptyList(), MReturn(resT)).also { it.body = emptyList() }
    val managedTake = MFun("gen.managedTake", MemMode.Managed, listOf(MParam(MLocal(1, "r", resT))), MReturn(MType.UNIT))
    val within = MFun("gen.within", MemMode.Strict, listOf(MParam(MLocal(1, "h", heapT), isContext = true)), MReturn(resT)).also { it.body = emptyList() }
}

private class MemGen(private val rng: Rng, private val env: MemEnv) {
    private var line = 1
    private var label = 1
    private var lambdaId = 1
    private val pools = ArrayList<ArrayList<MLocal>>()
    private val loops = ArrayList<Int>()
    private var inCtx = 0

    private fun pos(): Pos = Pos(line++, 5)

    private fun owners(): List<MLocal> = pools.flatten().filter { it.type === env.resT || it.type === env.resN }

    private fun newRes(p: Pos): MExpr = MExpr.New(env.ctor, env.cls, emptyList(), env.resT, p)

    private fun useOf(l: MLocal, p: Pos): MExpr = MExpr.Local(l, p)

    private fun block(depth: Int, n: Int): List<MStmt> {
        pools += ArrayList<MLocal>()
        val out = ArrayList<MStmt>()
        repeat(n) { stmt(depth, out) }
        pools.removeAt(pools.size - 1)
        return out
    }

    private fun stmt(depth: Int, out: MutableList<MStmt>) {
        val os = owners()
        val k = if (depth <= 0) rng.below(9) else rng.below(22)
        val p = pos()
        when (k) {
            0, 1 -> {
                val l = env.local("r", env.resT, p)
                out += MStmt.Decl(l, newRes(p), pos = p)
                pools.last() += l
            }
            2 -> {
                val l = env.local("m", env.resT, p)
                out += MStmt.Decl(l, MExpr.Call(env.make, null, emptyList(), env.resT, p), pos = p)
                pools.last() += l
            }
            3 -> if (os.isNotEmpty()) out += MStmt.Eval(MExpr.Free(useOf(rng.pick(os), p), null, FreeKind.Free, p), p)
            4 -> if (os.isNotEmpty()) out += MStmt.Eval(MExpr.Call(env.consume, null, listOf(useOf(rng.pick(os), p)), MType.UNIT, p), p)
            5 -> if (os.isNotEmpty()) out += MStmt.Eval(MExpr.Call(env.borrow, null, listOf(useOf(rng.pick(os), p)), MType.UNIT, p), p)
            6 -> if (os.isNotEmpty()) {
                val l = env.local("alias", env.resT, p)
                out += MStmt.Decl(l, useOf(rng.pick(os), p), pos = p)
                pools.last() += l
            }
            7 -> if (os.isNotEmpty()) {
                val t = rng.pick(os)
                val v: MExpr = when (rng.below(3)) {
                    0 -> newRes(p)
                    1 -> MExpr.Null(env.resN, p)
                    else -> useOf(rng.pick(os), p)
                }
                out += MStmt.Assign(t, v, p)
            }
            8 -> if (os.isNotEmpty()) {
                val l = env.local("moved", env.resT, p)
                out += MStmt.Decl(l, MExpr.Move(useOf(rng.pick(os), p), false, p), pos = p)
                pools.last() += l
            }
            9, 10 -> if (os.isNotEmpty()) {
                val t = rng.pick(os)
                out += MStmt.If(MExpr.NullTest(useOf(t, p), rng.chance(50), p), block(depth - 1, 1 + rng.below(3)), if (rng.chance(50)) block(depth - 1, 1 + rng.below(2)) else emptyList(), p)
            } else out += MStmt.If(MExpr.Opaque(MType.PRIM, p), block(depth - 1, 2), emptyList(), p)
            11 -> {
                val lab = label++
                loops += lab
                val inner = block(depth - 1, 1 + rng.below(3)) + MStmt.If(MExpr.Opaque(MType.PRIM, pos()), listOf(if (rng.chance(50)) MStmt.Break(lab, pos()) else MStmt.Continue(lab, pos())), emptyList(), pos())
                loops.removeAt(loops.size - 1)
                out += MStmt.While(lab, emptyList(), MExpr.Opaque(MType.PRIM, p), inner, p)
            }
            12 -> {
                val lab = label++
                loops += lab
                val inner = block(depth - 1, 1 + rng.below(2))
                loops.removeAt(loops.size - 1)
                out += MStmt.DoWhile(lab, inner, emptyList(), MExpr.Opaque(MType.PRIM, p), p)
            }
            13 -> if (os.isNotEmpty()) out += MStmt.Return(useOf(rng.pick(os), p), 0, p) else out += MStmt.Return(null, 0, p)
            14 -> if (os.size >= 2) {
                val a = rng.pick(os)
                val b = rng.pick(os)
                out += MStmt.SetField(useOf(a, p), env.nextField, useOf(b, p), p)
            }
            15 -> if (os.isNotEmpty()) {
                val l = env.local("n", env.resN, p)
                out += MStmt.Decl(l, MExpr.FieldGet(useOf(rng.pick(os), p), env.nextField, env.resN, p), pos = p)
                pools.last() += l
            }
            16 -> {
                val body = block(depth - 1, 1 + rng.below(3)) + MStmt.If(MExpr.Opaque(MType.PRIM, pos()), listOf(MStmt.Throw(MExpr.Opaque(MType.OTHER, pos()), pos())), emptyList(), pos())
                val catches = listOf(MStmt.Catch(null, block(depth - 1, 1 + rng.below(2))))
                val fin: List<MStmt>? = if (rng.chance(40)) block(depth - 1, 1) else null
                out += MStmt.Try(body, catches, fin, p)
            }
            17 -> if (os.isNotEmpty()) out += MStmt.Eval(MExpr.Call(env.managedTake, null, listOf(useOf(rng.pick(os), p)), MType.UNIT, p), p)
            18 -> {
                // arena scope: objects allocated inside live in the arena
                val a = env.local("arena", env.arenaT, p)
                out += MStmt.Decl(a, MExpr.New(env.arenaCtor, null, emptyList(), env.arenaT, p), pos = p)
                pools += ArrayList<MLocal>()
                val inner = ArrayList<MStmt>()
                val q = pos()
                val ctx = ArrayList<MLocal>()
                ctx += a
                val o = env.local("ao", env.resT, q)
                inner += MStmt.Decl(o, newRes(q), pos = q)
                pools.last() += o
                if (rng.chance(40)) inner += MStmt.Eval(MExpr.Free(useOf(o, pos()), null, FreeKind.Free, pos()), pos())
                if (rng.chance(30) && os.isNotEmpty()) inner += MStmt.Assign(rng.pick(os), useOf(o, pos()), pos())
                pools.removeAt(pools.size - 1)
                val id = lambdaId++
                out += MStmt.Scope(ScopeKind.Context, id, null, ctx, inner, emptyList(), q, p)
                if (rng.chance(70)) out += MStmt.Eval(MExpr.Free(useOf(a, pos()), null, FreeKind.Close, pos()), pos())
            }
            19 -> {
                val l = env.local("p", env.plainT, p)
                out += MStmt.Decl(l, MExpr.New(env.plainCtor, env.plainCls, emptyList(), env.plainT, p), pos = p)
                if (rng.chance(50)) out += MStmt.Return(useOf(l, pos()), 0, pos())
            }
            20 -> {
                // an escaping lambda capturing an owner
                if (os.isNotEmpty()) {
                    val cap = rng.pick(os)
                    val inner = listOf<MStmt>(MStmt.Eval(MExpr.Call(env.borrow, null, listOf(useOf(cap, pos())), MType.UNIT, pos()), pos()))
                    val lam = MExpr.Lambda(lambdaId++, emptyList(), inner, listOf(cap), p)
                    out += MStmt.Eval(lam, p)
                }
            }
            else -> {
                if (inCtx == 0) {
                    inCtx++
                    val h = env.local("heap", env.heapT, p)
                    val q = pos()
                    val inner = ArrayList<MStmt>()
                    val l = env.local("hr", env.resT, q)
                    inner += MStmt.Decl(l, MExpr.Call(env.within, null, listOf(MExpr.Local(h, q)), env.resT, q), pos = q)
                    if (rng.chance(50)) inner += MStmt.Eval(MExpr.Free(useOf(l, pos()), null, FreeKind.Free, pos()), pos())
                    out += MStmt.Decl(h, MExpr.New(env.arenaCtor, null, emptyList(), env.heapT, p), pos = p)
                    out += MStmt.Scope(ScopeKind.Once, lambdaId++, null, listOf(h), inner, emptyList(), q, p)
                    inCtx--
                }
            }
        }
    }

    fun function(index: Int): MFunction {
        line = 1
        pools.clear()
        loops.clear()
        val ps = ArrayList<MParam>()
        val locals = ArrayList<MLocal>()
        for (j in 0 until rng.below(3)) {
            val l = env.local("p$j", env.resT, Pos(0, 0))
            val annot = when (rng.below(3)) {
                0 -> MAnnot(owned = Pos(0, 1))
                1 -> MAnnot(borrowed = Pos(0, 1))
                else -> MAnnot.NONE
            }
            ps += MParam(l, annot)
            locals += l
        }
        val ret = if (rng.chance(50)) MReturn(env.resT) else MReturn(MType.UNIT)
        val allocCtx = rng.chance(40)
        if (allocCtx) {
            val h = env.local("h", env.heapT, Pos(0, 0))
            ps += MParam(h, isContext = true)
        }
        val fn = MFun("gen.g$index", MemMode.Strict, ps, ret, pos = Pos(line, 1))
        pools += ArrayList(locals)
        val body = ArrayList<MStmt>()
        repeat(3 + rng.below(5)) { stmt(3, body) }
        pools.clear()
        fn.body = body
        return MFunction(fn, body)
    }
}

private fun memModule(rng: Rng, index: Int): MModule {
    val env = MemEnv()
    val gen = MemGen(rng, env)
    val fns = ArrayList<MFunction>()
    fns += MFunction(env.consume, env.consume.body!!)
    fns += MFunction(env.borrow, env.borrow.body!!)
    for (i in 0 until 4) fns += gen.function(i)
    val sources = ArrayList<String>()
    for (i in 1..400) sources += "line $i of generated program $index"
    return MModule("gen$index.kt", fns, listOf(env.cls, env.plainCls, env.managedCls), mapOf("gen$index.kt" to sources))
}

/** A handcrafted program per interesting diagnostic. */
private fun memHandcrafted(): List<MModule> {
    val out = ArrayList<MModule>()
    val env = MemEnv()
    // leak: allocation never released
    run {
        val fn = MFun("leak", MemMode.Strict, emptyList(), MReturn(MType.UNIT), pos = Pos(1, 1))
        val a = env.local("a", env.resT, Pos(2, 9))
        val body = listOf<MStmt>(MStmt.Decl(a, MExpr.New(env.ctor, env.cls, emptyList(), env.resT, Pos(2, 13)), pos = Pos(2, 5)))
        fn.body = body
        out += MModule("leak.kt", listOf(MFunction(fn, body)), listOf(env.cls), mapOf("leak.kt" to listOf("fun leak() {", "    val a = Res()", "}")))
    }
    // use after free, double free, free of an alias, move then use
    run {
        val fn = MFun("misuse", MemMode.Strict, emptyList(), MReturn(MType.UNIT), pos = Pos(1, 1))
        val a = env.local("a", env.resT, Pos(2, 9))
        val b = env.local("b", env.resT, Pos(3, 9))
        val c = env.local("c", env.resT, Pos(4, 9))
        val body = listOf<MStmt>(
            MStmt.Decl(a, MExpr.New(env.ctor, env.cls, emptyList(), env.resT, Pos(2, 13)), pos = Pos(2, 5)),
            MStmt.Decl(b, MExpr.Local(a, Pos(3, 13)), pos = Pos(3, 5)),
            MStmt.Eval(MExpr.Free(MExpr.Local(b, Pos(4, 5)), null, FreeKind.Free, Pos(4, 5)), Pos(4, 5)),
            MStmt.Eval(MExpr.Free(MExpr.Local(a, Pos(5, 5)), null, FreeKind.Free, Pos(5, 5)), Pos(5, 5)),
            MStmt.Eval(MExpr.Call(env.borrow, null, listOf(MExpr.Local(a, Pos(6, 12))), MType.UNIT, Pos(6, 5)), Pos(6, 5)),
            MStmt.Decl(c, MExpr.New(env.ctor, env.cls, emptyList(), env.resT, Pos(7, 13)), pos = Pos(7, 5)),
            MStmt.Eval(MExpr.Call(env.consume, null, listOf(MExpr.Local(c, Pos(8, 13))), MType.UNIT, Pos(8, 5)), Pos(8, 5)),
            MStmt.Eval(MExpr.Free(MExpr.Local(c, Pos(9, 5)), null, FreeKind.Free, Pos(9, 5)), Pos(9, 5)),
        )
        fn.body = body
        val src = (1..10).map { "line $it" }
        out += MModule("misuse.kt", listOf(MFunction(fn, body)), listOf(env.cls), mapOf("misuse.kt" to src))
    }
    // loops and branches: maybe-freed values
    run {
        val fn = MFun("branchy", MemMode.Strict, emptyList(), MReturn(MType.UNIT), pos = Pos(1, 1))
        val a = env.local("a", env.resT, Pos(2, 9))
        val body = listOf<MStmt>(
            MStmt.Decl(a, MExpr.New(env.ctor, env.cls, emptyList(), env.resT, Pos(2, 13)), pos = Pos(2, 5)),
            MStmt.If(
                MExpr.Opaque(MType.PRIM, Pos(3, 9)),
                listOf(MStmt.Eval(MExpr.Free(MExpr.Local(a, Pos(4, 9)), null, FreeKind.Free, Pos(4, 9)), Pos(4, 9))),
                emptyList(), Pos(3, 5),
            ),
            MStmt.While(
                1, emptyList(), MExpr.Opaque(MType.PRIM, Pos(6, 12)),
                listOf(MStmt.Eval(MExpr.Call(env.borrow, null, listOf(MExpr.Local(a, Pos(7, 16))), MType.UNIT, Pos(7, 9)), Pos(7, 9))),
                Pos(6, 5),
            ),
        )
        fn.body = body
        out += MModule("branchy.kt", listOf(MFunction(fn, body)), listOf(env.cls), mapOf("branchy.kt" to (1..9).map { "line $it" }))
    }
    return out
}

// ---- driver -------------------------------------------------------------------------------------------------

private fun emitAndPrint(name: String, m: MirModule) {
    println("==== C: $name")
    try {
        println(CEmitter.emit(m))
    } catch (e: EmitError) {
        println("emit error: ${e.message}")
    }
}

private fun checkAndPrint(name: String, m: MModule) {
    println("==== memory: $name")
    val diags = MemoryChecker.check(m)
    for (d in diags) {
        println(DiagRender.brief(d))
        println(DiagRender.render(d, m.fileName, m.sources[m.fileName]))
    }
    println("diagnostics: ${diags.size}")
}

fun main() {
    emitAndPrint("demo", demoModule())
    emitAndPrint("arithmetic", arithmeticModule())
    emitAndPrint("loops", loopsModule())
    emitAndPrint("classes", classesModule())

    val rng = Rng(20240229L)
    val gen = MirGen(rng)
    for (m in 0 until 30) {
        gen.reset()
        val fns = ArrayList<MirFunction>()
        for (i in 0 until 6) fns += gen.function(i)
        emitAndPrint("generated $m", MirModule("gen$m.kt", fns))
    }

    println("==== memory checker")
    for ((i, m) in memHandcrafted().withIndex()) checkAndPrint("handcrafted $i", m)
    val mrng = Rng(77L)
    var total = 0
    val perCode = HashMap<String, Int>()
    for (i in 0 until 120) {
        val m = memModule(mrng, i)
        val diags = MemoryChecker.check(m)
        total += diags.size
        for (d in diags) perCode[d.code] = (perCode[d.code] ?: 0) + 1
        if (i < 15) checkAndPrint("generated $i", m)
    }
    println("generated programs: 120, diagnostics: $total")
    for (code in perCode.keys.sorted()) println("  $code: ${perCode[code]}")
}
