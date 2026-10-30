package mini.backend

/**
 * Which functions can leave with a pending exception? The emitter checks `mk_pending_exception`
 * only after calls that can throw, so straight-line numeric code stays free of checks.
 *
 * A function may throw when it contains a `throw`, an operation that raises (checked cast,
 * unboxing, `!!`, division, array access ...), a call of a function that may throw, or a dynamic
 * call (virtual / interface) while any function reachable through a vtable or itable may throw.
 * Computed as a fixpoint over the module.
 */
internal class ThrowAnalysis(private val module: MirModule) {
    val mayThrow = HashSet<String>()
    var dynamicMayThrow = false
        private set

    private val byName = HashMap<String, MirFunction>()

    fun run(): ThrowAnalysis {
        for (f in module.functions) byName[f.cName] = f
        val dyn = HashSet<String>()
        for (c in module.classes) {
            c.vtable.filterNotNull().forEach { dyn += it }
            for (t in c.itables) t.entries.filterNotNull().forEach { dyn += it }
        }
        var changed = true
        while (changed) {
            changed = false
            for (f in module.functions) {
                if (f.cName in mayThrow) continue
                if (bodyThrows(f)) {
                    mayThrow += f.cName
                    changed = true
                }
            }
            if (!dynamicMayThrow && dyn.any { it in mayThrow }) {
                dynamicMayThrow = true
                changed = true
            }
        }
        return this
    }

    private fun bodyThrows(f: MirFunction): Boolean = f.body.any { stmt(it) }

    private fun stmt(s: MirStmt): Boolean = when (s) {
        is MirStmt.Declare -> s.init?.let { expr(it) } ?: false
        is MirStmt.Assign -> expr(s.value)
        is MirStmt.SetField -> expr(s.obj) || expr(s.value)
        is MirStmt.Store -> expr(s.ptr) || expr(s.value)
        is MirStmt.Eval -> expr(s.expr)
        is MirStmt.If -> expr(s.cond) || s.thenBody.any { stmt(it) } || s.elseBody.any { stmt(it) }
        is MirStmt.Block -> s.body.any { stmt(it) }
        is MirStmt.While -> expr(s.cond) || s.body.any { stmt(it) }
        is MirStmt.Loop -> s.body.any { stmt(it) }
        is MirStmt.DoWhile -> s.body.any { stmt(it) } || s.condStmts.any { stmt(it) } || expr(s.cond)
        is MirStmt.ForRange ->
            expr(s.start) || expr(s.end) || (s.step != null && (expr(s.step) || !positiveConst(s.step))) ||
                s.body.any { stmt(it) }
        is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> false
        is MirStmt.Return -> s.value?.let { expr(it) } ?: false
        is MirStmt.Throw -> true
        is MirStmt.Try -> s.body.any { stmt(it) } || s.catches.any { c -> c.body.any { stmt(it) } }
    }

    private fun expr(e: MirExpr): Boolean = when (e) {
        is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst, is MirExpr.CharConst,
        is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local, is MirExpr.NullConst, is MirExpr.Unreachable,
        is MirExpr.CaughtException, is MirExpr.TypeInfoOf, is MirExpr.ObjectGet -> false
        is MirExpr.Binary -> expr(e.left) || expr(e.right) || divThrows(e)
        is MirExpr.Unary -> expr(e.operand)
        is MirExpr.Convert -> expr(e.operand)
        is MirExpr.Call -> e.args.any { expr(it) } || e.cName in mayThrow
        is MirExpr.RuntimeCall -> e.args.any { expr(it) } || runtimeCallThrows(e.cName)
        is MirExpr.Conditional -> expr(e.cond) || expr(e.thenValue) || expr(e.elseValue)
        is MirExpr.Box -> expr(e.operand)
        is MirExpr.Unbox -> true
        is MirExpr.Cast -> expr(e.operand) || e.mode == MirExpr.CastMode.Checked
        is MirExpr.IsInstance -> expr(e.operand)
        is MirExpr.NotNull -> true
        is MirExpr.FieldGet -> expr(e.obj)
        is MirExpr.VCall -> expr(e.receiver) || e.args.any { expr(it) } || dynamicMayThrow
        is MirExpr.ICall -> expr(e.receiver) || e.args.any { expr(it) } || dynamicMayThrow
        is MirExpr.New -> e.args.any { expr(it) } || e.ctorCName in mayThrow
        is MirExpr.StringLength -> expr(e.operand)
        is MirExpr.Load -> expr(e.ptr)
        is MirExpr.PtrAdd -> expr(e.ptr) || expr(e.bytes)
        // C code cannot throw; a staticCFunction callback never lets an exception escape (it panics instead)
        is MirExpr.CCall -> e.args.any { expr(it) }
        is MirExpr.CCallIndirect -> expr(e.callee) || e.args.any { expr(it) }
        is MirExpr.FuncAddr -> false
    }

    private fun positiveConst(e: MirExpr) = (e is MirExpr.IntConst && e.value > 0) || (e is MirExpr.LongConst && e.value > 0L)

    private fun divThrows(e: MirExpr.Binary): Boolean {
        if (e.op != BinaryOp.Div && e.op != BinaryOp.Rem) return false
        if (e.left.type != TInt && e.left.type != TLong) return false
        val r = e.right
        return !((r is MirExpr.IntConst && r.value != 0) || (r is MirExpr.LongConst && r.value != 0L))
    }

    companion object {
        /** Runtime functions that may leave an exception pending. */
        fun runtimeCallThrows(name: String): Boolean =
            name == "mk_to_string" || name == "mk_equals" || name == "mk_hash_code" || name == "mk_compare_to" ||
                name.startsWith("mk_checked_") || name.startsWith("mk_raise") || name == "mk_string_get_char" || name == "mk_throw" ||
                (name.startsWith("mk_array_") && name != "mk_array_length") || name == "mk_throwable_cause_message"
    }
}
