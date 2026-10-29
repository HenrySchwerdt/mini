package selfhost

import mini.backend.*
import mini.backend.memory.*

// v1-A driver: exercises the pure-Kotlin backend. Its output must be identical when this program runs
// on the JVM (real Kotlin) and natively (compiled by minic together with compiler/backend).

private fun buildModule(): MirModule {
    val n = MirLocal(0, "n", TInt)
    val acc = MirLocal(1, "acc", TInt)
    val i = MirLocal(2, "i", TInt)
    val label = MirLabel(0)
    val sum = MirFunction(
        "demo.sum", Mangling.function("demo", "sum", listOf(TInt)), listOf(n), TInt,
        listOf(
            MirStmt.Declare(acc, MirExpr.IntConst(0)),
            MirStmt.ForRange(
                label, i, MirExpr.IntConst(1), MirExpr.Local(n), null, true, true,
                listOf(MirStmt.Assign(acc, MirExpr.Binary(BinaryOp.Add, MirExpr.Local(acc), MirExpr.Local(i)))),
            ),
            MirStmt.Return(MirExpr.Local(acc)),
        ),
    )
    val r = MirLocal(3, "r", TInt)
    val s = MirLocal(4, "s", TString)
    val main = MirFunction(
        "main", Mangling.function("", "main", emptyList()), emptyList(), TUnit,
        listOf(
            MirStmt.Declare(r, MirExpr.Call(sum.cName, listOf(MirExpr.IntConst(10)), TInt)),
            MirStmt.Declare(s, MirExpr.StringConst("sum = \"x\"\n")),
            MirStmt.If(
                MirExpr.Binary(BinaryOp.Gt, MirExpr.Local(r), MirExpr.IntConst(50)),
                listOf(MirStmt.Eval(MirExpr.RuntimeCall("mk_println_string", listOf(MirExpr.Local(s)), TUnit))),
                listOf(MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Local(r)), TUnit))),
            ),
            MirStmt.Eval(MirExpr.RuntimeCall("mk_println_int", listOf(MirExpr.Binary(BinaryOp.Div, MirExpr.Local(r), MirExpr.IntConst(0))), TUnit)),
        ),
    )
    return MirModule("demo.kt", listOf(sum, main))
}

private fun buildMem(): MModule {
    val cls = MClass("demo.Res", MemMode.Strict, null, false)
    val ctor = MFun("demo.Res.<init>", MemMode.Strict, emptyList(), MReturn(MType.OTHER), ctorOf = cls)
    val resType = MType(TK.Strict, cls = cls, className = "demo.Res", display = "Res")
    val a = MLocal(1, "a", resType, Pos(3, 9))
    val b = MLocal(2, "b", resType, Pos(4, 9))
    val fn = MFun("demo.leaky", MemMode.Strict, emptyList(), MReturn(MType.UNIT), pos = Pos(2, 1))
    val body = listOf<MStmt>(
        MStmt.Decl(a, MExpr.New(ctor, cls, emptyList(), resType, Pos(3, 13)), pos = Pos(3, 5)),
        MStmt.Decl(b, MExpr.New(ctor, cls, emptyList(), resType, Pos(4, 13)), pos = Pos(4, 5)),
        MStmt.Eval(MExpr.Free(MExpr.Local(a, Pos(5, 5)), null, FreeKind.Free, Pos(5, 5)), Pos(5, 5)),
        MStmt.Eval(MExpr.Free(MExpr.Local(a, Pos(6, 5)), null, FreeKind.Free, Pos(6, 5)), Pos(6, 5)),
    )
    fn.body = body
    val sources = mapOf("demo.kt" to listOf("class Res", "fun leaky() {", "    val a = Res()", "    val b = Res()", "    a.free()", "    a.free()", "}"))
    return MModule("demo.kt", listOf(MFunction(fn, body)), listOf(cls), sources)
}

fun main() {
    val c = CEmitter.emit(buildModule())
    println(c)
    println("---- memory checker ----")
    val mm = buildMem()
    val diags = MemoryChecker.check(mm)
    for (d in diags) {
        println(DiagRender.brief(d))
        println(DiagRender.render(d, mm.fileName, mm.sources[mm.fileName]))
    }
    println("diagnostics: ${diags.size}")
}
