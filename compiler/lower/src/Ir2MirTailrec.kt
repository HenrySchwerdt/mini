package mini.lower

import mini.backend.*
import mini.frontend.*
import mini.frontend.sema.*

/*
 * Ir2Mir: `tailrec`. A call of the function itself in tail position (`return f(...)`, the branches of a returned `if` / `when`)
 * assigns the parameters and jumps to the start of the body instead of calling: the body runs in a loop over copies of the parameters.
 */

/** The state of the `tailrec` function being converted: the loop and the mutable copies of its parameters. */
internal class TailLoop(val label: MirLabel, val copies: Map<IrValueParameter, MirLocal>, val calls: Set<IrCall>)

private fun isSelfCall(e: IrExpression, f: IrSimpleFunction): Boolean {
    if (e !is IrCall || e.callee !== f || e.superQualifier != null) return false
    // the receivers must stay the same
    for ((i, p) in f.parameters.withIndex()) {
        if (p.kind == IrParameterKind.Regular) continue
        val a = e.arguments.getOrNull(i)
        var x: IrExpression? = a
        while (x is IrTypeOperatorCall && x.operator == IrTypeOperator.IMPLICIT_CAST) x = x.argument
        if (x !is IrGetValue || x.owner !== p) return false
    }
    return true
}

private fun collectTail(e: IrExpression?, f: IrSimpleFunction, out: MutableSet<IrCall>) {
    when (e) {
        is IrCall -> if (isSelfCall(e, f)) out += e
        is IrWhen -> for (b in e.branches) collectTail(b.result, f, out)
        is IrContainerExpression -> {
            if (e.origin == "FOR_LOOP") return
            collectTail(e.statements.lastOrNull() as? IrExpression, f, out)
        }
        is IrTypeOperatorCall -> if (e.operator == IrTypeOperator.IMPLICIT_CAST || e.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) collectTail(e.argument, f, out)
        else -> {}
    }
}

/** The self calls of [f] that are in tail position. */
internal fun tailCallsOf(f: IrSimpleFunction): Set<IrCall> {
    val found = LinkedHashSet<IrCall>()
    val w = object : IrWalker() {
        override fun visit(e: IrElement) {
            if (e is IrReturn && e.returnTarget === f) collectTail(e.value, f, found)
            if (e is IrFunctionExpression || (e is IrClass)) return
            visitChildren(e)
        }
    }
    val b = f.body
    if (b is IrBlockBody && f.returnType.classFqName == "kotlin.Unit" && b.statements.isNotEmpty()) {
        // in a Unit function the last statement is in tail position: `return` it
        val last = b.statements[b.statements.size - 1]
        if (last is IrExpression && last !is IrReturn) {
            val r = IrReturn(f, last, f.returnType)
            r.startOffset = last.startOffset
            b.statements[b.statements.size - 1] = r
        }
    }
    if (b is IrBlockBody) for (s in b.statements) w.visit(s)
    else if (b is IrExpressionBody) w.visit(b.expression)
    return found
}

/** Does the returned expression [e] contain a tail call? */
internal fun Ir2Mir.hasTailCall(e: IrExpression, calls: Set<IrCall>): Boolean {
    val acc = LinkedHashSet<IrCall>()
    // tail positions of e (the same walk as for the function)
    fun go(x: IrExpression?) {
        when (x) {
            is IrCall -> if (x in calls) acc += x
            is IrWhen -> for (b in x.branches) go(b.result)
            is IrContainerExpression -> if (x.origin != "FOR_LOOP") go(x.statements.lastOrNull() as? IrExpression)
            is IrTypeOperatorCall -> if (x.operator == IrTypeOperator.IMPLICIT_CAST || x.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) go(x.argument)
            else -> {}
        }
    }
    go(e)
    return acc.isNotEmpty()
}

/** `return [e]` of the `tailrec` function: tail calls become jumps, everything else returns as usual. */
internal fun Ir2Mir.returnWithTail(e: IrExpression, tl: TailLoop, out: MutableList<MirStmt>) {
    when {
        e is IrCall && e in tl.calls -> tailJump(e, tl, out)
        e is IrWhen && hasTailCall(e, tl.calls) -> {
            val pieces = e.branches.map { br ->
                val cs = ArrayList<MirStmt>()
                val c = if (isConstTrue(br.condition)) null else value(br.condition, cs)
                val body = ArrayList<MirStmt>()
                returnWithTail(br.result, tl, body)
                Triple(cs, c, body)
            }
            fun build(i: Int): List<MirStmt> {
                if (i >= pieces.size) {
                    // an `if` without `else` in a Unit function: falls out of the function
                    return if (currentReturn == TUnit) listOf(MirStmt.Return(if (unitAsNull) unitInstance() else null))
                    else listOf(MirStmt.Panic("NoWhenBranchMatchedException"))
                }
                val (cs, c, body) = pieces[i]
                if (c == null) return body
                return cs + MirStmt.If(c, body, build(i + 1))
            }
            out += build(0)
        }
        e is IrContainerExpression && e.origin != "FOR_LOOP" && hasTailCall(e, tl.calls) -> {
            val inner = ArrayList<MirStmt>()
            for ((i, s) in e.statements.withIndex()) {
                if (i == e.statements.size - 1 && s is IrExpression) returnWithTail(s, tl, inner) else stmt(s, inner)
            }
            out += MirStmt.Block(inner)
        }
        e is IrTypeOperatorCall && hasTailCall(e, tl.calls) -> returnWithTail(e.argument, tl, out)
        else -> {
            if (currentReturn == TUnit) {
                stmt(e, out)
                val un = unitAsNull
                leaveThrough(0, out) { it += MirStmt.Return(if (un) unitInstance() else null) }
            } else returnValue(e, out)
        }
    }
}

private fun Ir2Mir.tailJump(c: IrCall, tl: TailLoop, out: MutableList<MirStmt>) {
    val callee = c.callee
    val vals = callArgs(c, callee, out)
    val fresh = ArrayList<Pair<MirLocal, MirExpr>>()
    for ((i, p) in callee.parameters.withIndex()) {
        if (p.kind != IrParameterKind.Regular) continue
        val copy = tl.copies[p] ?: unsupported(c, "tail call of '${callee.name}'")
        val v = vals[i]
        // every new value is computed before any parameter changes (`f(b, a)`)
        val held = if (v is MirExpr.IntConst || v is MirExpr.BooleanConst || v is MirExpr.StringConst || v is MirExpr.NullConst || v is MirExpr.LongConst || v is MirExpr.DoubleConst) v else tmpOfPlain(v, out)
        fresh += copy to held
    }
    for ((copy, v) in fresh) out += MirStmt.Assign(copy, v)
    out += MirStmt.Continue(tl.label)
}
