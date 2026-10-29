
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Ir2Mir: loops, for-ranges, when / if. */

// ---- loops ------------------------------------------------------------------------

internal fun Ir2Mir.flattenBody(e: IrExpression?, out: MutableList<MirStmt>) {
    if (e != null) stmt(e, out)
}

internal fun Ir2Mir.whileLoop(s: IrWhileLoop, out: MutableList<MirStmt>) {
    val label = MirLabel(labelCounter++)
    loopLabels[s] = label
    loopFinallyDepth[s] = finallyStack.size
    val pre = ArrayList<MirStmt>()
    val cond = value(s.condition, pre)
    val body = ArrayList<MirStmt>()
    if (pre.isEmpty()) {
        flattenBody(s.body, body)
        out += MirStmt.While(label, cond, body)
    } else {
        body += pre
        body += MirStmt.If(MirExpr.Unary(UnaryOp.Not, cond), listOf(MirStmt.Break(label)), emptyList())
        flattenBody(s.body, body)
        out += MirStmt.Loop(label, body)
    }
}

internal fun Ir2Mir.doWhileLoop(s: IrDoWhileLoop, out: MutableList<MirStmt>) {
    val label = MirLabel(labelCounter++)
    loopLabels[s] = label
    loopFinallyDepth[s] = finallyStack.size
    val body = ArrayList<MirStmt>()
    flattenBody(s.body, body)
    val pre = ArrayList<MirStmt>()
    val cond = value(s.condition, pre)
    out += MirStmt.DoWhile(label, body, pre, cond)
}


internal fun Ir2Mir.progression(e: IrExpression): Progression =
    progressionOrNull(e) ?: unsupported(e, "for loop over something other than an Int/Long/Char range built with `..`, `until`, `downTo` or `step`")

/** The literal progression shape `a..b`, `a until b`, `a downTo b`, `p step n`; null for any other expression (a range value). */
internal fun Ir2Mir.progressionOrNull(e: IrExpression): Progression? {
    if (e is IrCall) {
        val owner = e.callee
        val parent = owner.parent
        val name = owner.name
        val args = e.arguments
        if (parent is IrFile && parent.packageFqName == "kotlin.ranges") {
            when (name) {
                "downTo" -> return Progression(args[0]!!, args[1]!!, null, ascending = false, inclusive = true)
                "until" -> return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = false)
                "step" -> {
                    val inner = progressionOrNull(args[0]!!) ?: return null
                    if (inner.step != null) return null
                    return Progression(inner.start, inner.end, args[1]!!, inner.ascending, inner.inclusive)
                }
            }
        }
        if (parent is IrClass && name == "rangeTo") {
            val fq = parent.kotlinFqName
            if (fq == "kotlin.Int" || fq == "kotlin.Long" || fq == "kotlin.Char") {
                return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = true)
            }
        }
    }
    return null
}

/** `for (x in range)`: fir2ir leaves the iterator protocol in place; match it. */
internal fun Ir2Mir.forLoop(b: IrBlock, out: MutableList<MirStmt>) {
    val st = b.statements
    val iter = st.getOrNull(0) as? IrVariable
    val loop = st.getOrNull(1) as? IrWhileLoop
    val iterCall = iter?.initializer as? IrCall
    if (iter == null || loop == null || iterCall == null || st.size != 2) unsupported(b, "for loop shape")
    if (arrayForLoop(b, loop, iterCall, out)) return
    val elemType = when (iter.type.classFqName) {
        "kotlin.collections.IntIterator" -> TInt
        "kotlin.collections.LongIterator" -> TLong
        "kotlin.collections.CharIterator" -> TChar
        else -> {
            // any other Iterable: the iterator protocol is ordinary code (iterator(), hasNext(), next())
            for (x in st) stmt(x, out)
            return
        }
    }
    val p = progressionOrNull(iterCall.arguments[0]!!)
    if (p == null) {
        // a range / progression value: ordinary iterator protocol over the stdlib classes
        for (x in st) stmt(x, out)
        return
    }

    val body = loop.body as? IrBlock ?: unsupported(loop, "for loop body")
    val loopVar = body.statements.getOrNull(0) as? IrVariable ?: unsupported(loop, "for loop variable")
    val label = MirLabel(labelCounter++)
    loopLabels[loop] = label
    loopFinallyDepth[loop] = finallyStack.size

    val exprs = listOfNotNull(p.start, p.end, p.step)
    val vals = operands(exprs, out)
    val start = convertTo(vals[0], elemType, b)
    val end = convertTo(vals[1], elemType, b)
    val step = vals.getOrNull(2)
    val mirVar = MirLocal(localCounter++, loopVar.name, elemType)
    locals[loopVar] = mirVar
    val inner = ArrayList<MirStmt>()
    for (x in body.statements.drop(1)) stmt(x, inner)
    out += MirStmt.ForRange(label, mirVar, start, end, step, p.ascending, p.inclusive, inner)
}

// ---- when / if --------------------------------------------------------------------

internal fun Ir2Mir.isConstTrue(e: IrExpression) = e is IrConst && e.value == true

internal fun Ir2Mir.whenStmt(w: IrWhen, out: MutableList<MirStmt>) = whenInto(w, out, null)

/** Lowers [w] as an if-chain. If [target] is not null every branch assigns its value to it. */
internal fun Ir2Mir.whenInto(w: IrWhen, out: MutableList<MirStmt>, target: MirLocal?) {
    class Piece(val condStmts: List<MirStmt>, val cond: MirExpr?, val body: List<MirStmt>)
    val pieces = w.branches.map { br ->
        val cs = ArrayList<MirStmt>()
        val c = if (isConstTrue(br.condition)) null else value(br.condition, cs)
        val body = ArrayList<MirStmt>()
        if (target != null && br.result.type.classFqName != "kotlin.Nothing") {
            body += MirStmt.Assign(target, bindValue(br.result, target.type, body))
        } else {
            stmt(br.result, body)
        }
        Piece(cs, c, body)
    }
    fun build(i: Int): List<MirStmt> {
        if (i >= pieces.size) return emptyList()
        val p = pieces[i]
        val c = p.cond ?: return p.body
        return p.condStmts + MirStmt.If(c, p.body, build(i + 1))
    }
    out += build(0)
}

internal fun Ir2Mir.whenValue(w: IrWhen, out: MutableList<MirStmt>): MirExpr {
    val type = mirType(w.type, w)
    if (type == TUnit || type == TNothing) {
        whenInto(w, out, null)
        return if (type == TUnit) MirExpr.UnitValue else MirExpr.Unreachable(TNothing)
    }
    // Short-circuit operators and pure if-else expressions over primitives become expressions.
    val br = w.branches
    val origin = w.origin
    if (br.size == 2 && isConstTrue(br[1].condition)) {
        val c = ArrayList<MirStmt>(); val a = ArrayList<MirStmt>(); val b = ArrayList<MirStmt>()
        val cv = value(br[0].condition, c)
        val av = value(br[0].result, a)
        val bv = value(br[1].result, b)
        if (c.isEmpty() && a.isEmpty() && b.isEmpty() && av.type != TNothing && bv.type != TNothing && !type.isRef &&
            !av.type.isRef && !bv.type.isRef
        ) {
            if (origin == "ANDAND") return MirExpr.Binary(BinaryOp.AndAnd, cv, av)
            if (origin == "OROR") return MirExpr.Binary(BinaryOp.OrOr, cv, bv)
            return MirExpr.Conditional(cv, av, bv, type)
        }
        // General form, reusing the pieces lowered above (no second lowering).
        val tmp = newTemp(type)
        out += MirStmt.Declare(tmp, null)
        out += c
        fun assign(vv: MirExpr, body: MutableList<MirStmt>, r: IrExpression) {
            if (vv.type != TNothing) body += MirStmt.Assign(tmp, convertTo(vv, type, r))
        }
        assign(av, a, br[0].result)
        assign(bv, b, br[1].result)
        out += MirStmt.If(cv, a, b)
        return MirExpr.Local(tmp)
    }
    val tmp = newTemp(type)
    out += MirStmt.Declare(tmp, null)
    whenInto(w, out, tmp)
    return MirExpr.Local(tmp)
}
