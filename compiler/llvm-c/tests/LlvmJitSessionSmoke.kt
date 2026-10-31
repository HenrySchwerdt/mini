package mini.llvmapi.tests

import mini.backend.MirFunction
import mini.backend.MirExpr
import mini.backend.MirExtern
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.TUnit
import mini.llvmapi.LlvmJitSession

/** Native smoke test for session reuse, unique entry points, pre-transfer failure recovery, and cleanup. */
fun main() {
    val jit = LlvmJitSession(0)
    try {
        jit.runModule(emptyProgram("first"), true, "repl_entry_1")
        jit.runModule(emptyProgram("second"), true, "repl_entry_2")

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
