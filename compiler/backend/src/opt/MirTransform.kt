package mini.backend

/*
 * A bottom-up rewriter of MIR trees for the optimization passes (opt/). Nodes are immutable, so a rewrite builds new nodes
 * and shares every subtree that did not change (the same object comes back when nothing changed). Locals, labels and
 * functions are shared. Subclasses override [expr] (call [rebuildExpr] first to rewrite the operands) and/or [stmt].
 */
open class MirTransform {
    /** Rewrites [e]; the default rewrites the operands only. */
    open fun expr(e: MirExpr): MirExpr = rebuildExpr(e)

    /** Rewrites [s]; the default rewrites the operands and nested bodies only. */
    open fun stmt(s: MirStmt): MirStmt = rebuildStmt(s)

    /** Rewrites a statement list; the default maps [stmt] over it. */
    open fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            val r = stmt(s)
            if (out == null && r !== s) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null) out.add(r)
        }
        return out ?: l
    }

    fun exprs(l: List<MirExpr>): List<MirExpr> {
        var out: ArrayList<MirExpr>? = null
        for (i in l.indices) {
            val e = l[i]
            val r = expr(e)
            if (out == null && r !== e) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null) out.add(r)
        }
        return out ?: l
    }

    fun rebuildExpr(e: MirExpr): MirExpr = when (e) {
        is MirExpr.Binary -> {
            val l = expr(e.left)
            val r = expr(e.right)
            if (l === e.left && r === e.right) e else MirExpr.Binary(e.op, l, r)
        }
        is MirExpr.Unary -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.Unary(e.op, o) }
        is MirExpr.Convert -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.Convert(o, e.to) }
        is MirExpr.Call -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.Call(e.cName, a, e.type) }
        is MirExpr.RuntimeCall -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.RuntimeCall(e.cName, a, e.type) }
        is MirExpr.Conditional -> {
            val c = expr(e.cond)
            val t = expr(e.thenValue)
            val f = expr(e.elseValue)
            if (c === e.cond && t === e.thenValue && f === e.elseValue) e else MirExpr.Conditional(c, t, f, e.resultType)
        }
        is MirExpr.Box -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.Box(o, e.to) }
        is MirExpr.Unbox -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.Unbox(o, e.to) }
        is MirExpr.Cast -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.Cast(o, e.to, e.mode) }
        is MirExpr.IsInstance -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.IsInstance(o, e.target) }
        is MirExpr.NotNull -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.NotNull(o) }
        is MirExpr.FieldGet -> { val o = expr(e.obj); if (o === e.obj) e else MirExpr.FieldGet(o, e.owner, e.field, e.type) }
        is MirExpr.VCall -> {
            val r = expr(e.receiver)
            val a = exprs(e.args)
            if (r === e.receiver && a === e.args) e else MirExpr.VCall(e.slot, r, a, e.paramTypes, e.type)
        }
        is MirExpr.ICall -> {
            val r = expr(e.receiver)
            val a = exprs(e.args)
            if (r === e.receiver && a === e.args) e else MirExpr.ICall(e.interfaceCName, e.index, r, a, e.paramTypes, e.type)
        }
        is MirExpr.New -> {
            val a = exprs(e.args)
            val al = e.allocator
            val al2 = if (al == null) null else expr(al)
            if (a === e.args && al2 === al) e else MirExpr.New(e.ctorCName, a, e.type, al2, e.auto)
        }
        is MirExpr.StringLength -> { val o = expr(e.operand); if (o === e.operand) e else MirExpr.StringLength(o) }
        is MirExpr.Load -> { val o = expr(e.ptr); if (o === e.ptr) e else MirExpr.Load(o, e.type) }
        is MirExpr.PtrAdd -> {
            val p = expr(e.ptr)
            val b = expr(e.bytes)
            if (p === e.ptr && b === e.bytes) e else MirExpr.PtrAdd(p, b)
        }
        is MirExpr.CCall -> { val a = exprs(e.args); if (a === e.args) e else MirExpr.CCall(e.extern, a) }
        is MirExpr.CCallIndirect -> {
            val c = expr(e.callee)
            val a = exprs(e.args)
            if (c === e.callee && a === e.args) e else MirExpr.CCallIndirect(c, a, e.paramTypes, e.type)
        }
        is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst, is MirExpr.CharConst,
        is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local, is MirExpr.NullConst, is MirExpr.ObjectGet,
        is MirExpr.Unreachable, is MirExpr.CaughtException, is MirExpr.TypeInfoOf, is MirExpr.FuncAddr -> e
    }

    fun <T : MirStmt> keep(old: MirStmt, n: T): T {
        n.srcLine = old.srcLine
        n.srcFile = old.srcFile
        return n
    }

    fun rebuildStmt(s: MirStmt): MirStmt = when (s) {
        is MirStmt.Declare -> {
            val i = s.init
            val n = if (i == null) null else expr(i)
            if (n === i) s else keep(s, MirStmt.Declare(s.local, n))
        }
        is MirStmt.Assign -> { val v = expr(s.value); if (v === s.value) s else keep(s, MirStmt.Assign(s.local, v)) }
        is MirStmt.SetField -> {
            val o = expr(s.obj)
            val v = expr(s.value)
            if (o === s.obj && v === s.value) s else keep(s, MirStmt.SetField(o, s.owner, s.field, s.fieldType, v))
        }
        is MirStmt.Store -> {
            val p = expr(s.ptr)
            val v = expr(s.value)
            if (p === s.ptr && v === s.value) s else keep(s, MirStmt.Store(p, v))
        }
        is MirStmt.Eval -> { val x = expr(s.expr); if (x === s.expr) s else keep(s, MirStmt.Eval(x)) }
        is MirStmt.If -> {
            val c = expr(s.cond)
            val t = stmts(s.thenBody)
            val f = stmts(s.elseBody)
            if (c === s.cond && t === s.thenBody && f === s.elseBody) s else keep(s, MirStmt.If(c, t, f))
        }
        is MirStmt.Block -> { val b = stmts(s.body); if (b === s.body) s else keep(s, MirStmt.Block(b)) }
        is MirStmt.While -> {
            val c = expr(s.cond)
            val b = stmts(s.body)
            if (c === s.cond && b === s.body) s else keep(s, MirStmt.While(s.label, c, b))
        }
        is MirStmt.Loop -> { val b = stmts(s.body); if (b === s.body) s else keep(s, MirStmt.Loop(s.label, b)) }
        is MirStmt.DoWhile -> {
            val b = stmts(s.body)
            val cs = stmts(s.condStmts)
            val c = expr(s.cond)
            if (b === s.body && cs === s.condStmts && c === s.cond) s else keep(s, MirStmt.DoWhile(s.label, b, cs, c))
        }
        is MirStmt.ForRange -> {
            val a = expr(s.start)
            val b = expr(s.end)
            val st = s.step
            val st2 = if (st == null) null else expr(st)
            val body = stmts(s.body)
            if (a === s.start && b === s.end && st2 === st && body === s.body) s
            else keep(s, MirStmt.ForRange(s.label, s.variable, a, b, st2, s.ascending, s.inclusive, body))
        }
        is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> s
        is MirStmt.Return -> {
            val v = s.value
            val n = if (v == null) null else expr(v)
            if (n === v) s else keep(s, MirStmt.Return(n))
        }
        is MirStmt.Throw -> { val v = expr(s.value); if (v === s.value) s else keep(s, MirStmt.Throw(v)) }
        is MirStmt.Try -> {
            val b = stmts(s.body)
            var changed = b !== s.body
            val cs = ArrayList<MirCatch>()
            for (c in s.catches) {
                val cb = stmts(c.body)
                if (cb !== c.body) { changed = true; cs.add(MirCatch(c.type, cb)) } else cs.add(c)
            }
            if (!changed) s else keep(s, MirStmt.Try(b, cs))
        }
    }
}

/** Visits every expression (parents before children) of statement lists; subclasses implement [visit]. */
abstract class MirVisitor : MirTransform() {
    abstract fun visit(e: MirExpr)

    override fun expr(e: MirExpr): MirExpr {
        visit(e)
        return rebuildExpr(e)
    }

    fun walk(body: List<MirStmt>) {
        stmts(body)
    }
}

/** A copy of [f] with another [body]. */
internal fun MirFunction.withBody(body: List<MirStmt>): MirFunction = MirFunction(
    qualifiedName, cName, params, returnType, body, constructorOf, strict, returnsBorrowed, ownsParams, noHeapEffects,
)
