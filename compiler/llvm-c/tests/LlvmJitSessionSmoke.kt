package mini.llvmapi.tests

import mini.backend.MirFunction
import mini.backend.MirExpr
import mini.backend.MirExtern
import mini.backend.MirClass
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.llvmapi.LlvmJitSession

/** Native smoke test for session reuse, unique entry points, pre-transfer failure recovery, and cleanup. */
fun main() {
    val jit = LlvmJitSession(0)
    try {
        jit.runModule(emptyProgram("first"), true, "repl_entry_1")
        jit.runModule(emptyProgram("second"), true, "repl_entry_2")

        // The constructor's second parameter becomes owned by inference because it forwards to a prior cell's consumer.
        val consumeArg = MirLocal(0, "value", TString)
        val consumer = MirFunction("test.consume", "mini_repl_consume", listOf(consumeArg), TUnit, emptyList(), ownsParams = listOf(0))
        jit.addModule(MirModule("consumer.kt", listOf(consumer)), true, null)

        val thisArg = MirLocal(0, "this", TObject("mini.repl.Node", "mini_repl_Node", false))
        val constructorArg = MirLocal(1, "value", TString)
        val ctorName = "mini_repl_Node_init"
        val node = MirClass("mini.repl.Node", "mini_repl_Node", false, null, emptyList(), emptyList(), listOf(null, null, null), emptyList())
        val constructor = MirFunction(
            "mini.repl.Node.<init>", ctorName, listOf(thisArg, constructorArg), TUnit,
            listOf(MirStmt.Eval(MirExpr.Call("mini_repl_consume", listOf(MirExpr.Local(constructorArg)), TUnit))),
            constructorOf = node.cName,
        )
        jit.addModule(MirModule("constructor.kt", listOf(constructor), listOf(node)), true, null)

        val callerLocal = MirLocal(0, "input", TString)
        val caller = MirFunction(
            "test.main", "mk_main", emptyList(), TUnit,
            listOf(
                MirStmt.Declare(callerLocal, MirExpr.RuntimeCall("mk_string_concat", listOf(MirExpr.StringConst("owned "), MirExpr.StringConst("argument")), TString)),
                MirStmt.Eval(MirExpr.New(ctorName, listOf(MirExpr.Local(callerLocal)), TObject(node.qualifiedName, node.cName, false))),
            ),
        )
        jit.runModule(MirModule("caller.kt", listOf(caller), listOf(node)), true, "constructor_forwarding_entry")

        var failedModeChange = false
        try {
            jit.runModule(emptyProgram("wrong-mode"), false, "wrong_mode_entry")
        } catch (_: IllegalArgumentException) {
            failedModeChange = true
        }
        check(failedModeChange) { "a persistent JIT session must reject reference-count mode changes" }
        jit.runModule(emptyProgram("same-mode-after-reject"), true, "same_mode_entry")

        var failed = false
        try {
            jit.runModule(MirModule("missing-main.kt", emptyList()), true, "repl_entry_bad")
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        check(failed) { "a module without an entry point should fail before entering ORC" }

        // A failed input must leave the same LLJIT usable for the next input.
        jit.runModule(emptyProgram("third"), true, "repl_entry_3")

        val missing = MirExtern("mini_repl_missing_symbol_8392", emptyList(), TUnit)
        val broken = MirModule(
            "unresolved.kt",
            listOf(MirFunction("unresolved.main", "mk_main", emptyList(), TUnit, listOf(MirStmt.Eval(MirExpr.CCall(missing, emptyList()))))),
            externs = listOf(missing),
        )
        failed = false
        try {
            jit.runModule(broken, true, "retry_entry")
        } catch (_: IllegalStateException) {
            failed = true
        }
        check(failed) { "an unresolved external should fail during JIT materialization" }
        jit.runModule(emptyProgram("retry"), true, "retry_entry")
    } finally {
        jit.close()
        jit.close()
    }
}

private fun emptyProgram(name: String): MirModule = MirModule(
    "$name.kt",
    listOf(MirFunction("$name.main", "mk_main", emptyList(), TUnit, emptyList())),
)
