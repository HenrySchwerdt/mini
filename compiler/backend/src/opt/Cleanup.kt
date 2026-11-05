package mini.backend

/*
 * Local clean-up after inlining and the other opt passes (one linear pass per round, a few rounds):
 *  - copy propagation: a local declared once, never reassigned, that merely aliases a never-reassigned local of the same type or a
 *    constant is replaced by it everywhere;
 *  - unused pure declarations and pure `eval`s are removed;
 *  - `declare x; ...; x = v` where the assignment is the first thing that mentions x becomes `declare x = v`;
 *  - `if` on a constant.
 */
internal object Cleanup {
    fun run(body: List<MirStmt>, f: MirFunction): List<MirStmt> {
        var cur = body
        var round = 0
        while (round < 3) {
            val u = UseScan(f)
            u.walk(cur)
            val pass = CleanupPass(u)
            val next = pass.stmts(cur)
            if (!pass.changed) break
            cur = next
            round++
        }
        return cur
    }
}

/** Counts uses and assignments per local id. */
internal class UseScan(val f: MirFunction) : MirVisitor() {
    val uses = HashMap<Int, Int>()
    val assigns = HashMap<Int, Int>()
    val inits = HashMap<Int, MirExpr>()
    val declared = HashMap<Int, Int>()

    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local) uses[e.local.id] = (uses[e.local.id] ?: 0) + 1
    }

    override fun stmt(s: MirStmt): MirStmt {
        when (s) {
            is MirStmt.Assign -> assigns[s.local.id] = (assigns[s.local.id] ?: 0) + 1
            is MirStmt.ForRange -> assigns[s.variable.id] = (assigns[s.variable.id] ?: 0) + 1
            is MirStmt.Declare -> {
                declared[s.local.id] = (declared[s.local.id] ?: 0) + 1
                val i = s.init
                if (i != null) inits[s.local.id] = i
            }
            else -> {}
        }
        return super.stmt(s)
    }

    fun useCount(l: MirLocal): Int = uses[l.id] ?: 0
    fun assignCount(l: MirLocal): Int = assigns[l.id] ?: 0
    fun isParam(l: MirLocal): Boolean {
        for (p in f.params) if (p === l) return true
        return false
    }
}

internal fun isPureExpr(e: MirExpr): Boolean = when (e) {
    is MirExpr.Local, is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst,
    is MirExpr.CharConst, is MirExpr.StringConst, is MirExpr.NullConst, is MirExpr.UnitValue -> true
    is MirExpr.Binary -> {
        val op = e.op
        if (op == BinaryOp.Div || op == BinaryOp.Rem) false else isPureExpr(e.left) && isPureExpr(e.right)
    }
    is MirExpr.Unary -> isPureExpr(e.operand)
    is MirExpr.Convert -> isPureExpr(e.operand)
    is MirExpr.Cast -> e.mode == MirExpr.CastMode.Unchecked && isPureExpr(e.operand)
    is MirExpr.IsInstance -> isPureExpr(e.operand)
    is MirExpr.FieldGet -> e.obj is MirExpr.Local && isPureExpr(e.obj)
    is MirExpr.StringLength -> e.operand is MirExpr.Local
    else -> false
}

internal fun isDefaultConst(e: MirExpr?): Boolean =
    e is MirExpr.IntConst || e is MirExpr.LongConst || e is MirExpr.DoubleConst || e is MirExpr.BooleanConst || e is MirExpr.CharConst || e is MirExpr.NullConst

/** Does a statement (or a nested one) mention a local: read it, assign it or declare it? */
internal class Mentions(val l: MirLocal) : MirVisitor() {
    var found = false
    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local && e.local === l) found = true
    }
    override fun stmt(s: MirStmt): MirStmt {
        if (found) return s
        if (s is MirStmt.Assign && s.local === l) found = true
        if (s is MirStmt.Declare && s.local === l) found = true
        if (s is MirStmt.ForRange && s.variable === l) found = true
        return super.stmt(s)
    }

    companion object {
        fun of(s: MirStmt, l: MirLocal): Boolean {
            val m = Mentions(l)
            m.stmt(s)
            return m.found
        }

        fun exprUses(e: MirExpr, l: MirLocal): Boolean {
            val m = Mentions(l)
            m.expr(e)
            return m.found
        }
    }
}

private class CleanupPass(val u: UseScan) : MirTransform() {
    var changed = false
    /** Aliases: local id -> the expression that stands for it. */
    val alias = HashMap<Int, MirExpr>()
    var inDoWhile = 0
    var prepared = false

    private fun prepare() {
        prepared = true
        for ((id, init) in u.inits) {
            if (u.declared[id] != 1 || u.assigns[id] != null) continue
            if (init is MirExpr.IntConst || init is MirExpr.LongConst || init is MirExpr.DoubleConst || init is MirExpr.BooleanConst || init is MirExpr.CharConst) {
                alias[id] = init
            } else if (init is MirExpr.Local) {
                if (init.local.id != id && u.assigns[init.local.id] == null) alias[id] = init
            }
        }
    }

    private fun resolve(l: MirLocal): MirExpr? {
        var r = alias[l.id] ?: return null
        var guard = 0
        while (r is MirExpr.Local && guard < 50) {
            val n = alias[r.local.id] ?: break
            r = n
            guard++
        }
        return r
    }

    override fun expr(e: MirExpr): MirExpr {
        if (e is MirExpr.Local) {
            val r = resolve(e.local)
            if (r != null && r.type == e.local.type) {
                changed = true
                return cloneLeaf(r)
            }
            return e
        }
        return rebuildExpr(e)
    }

    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.DoWhile) {
            inDoWhile++
            val r = rebuildStmt(s)
            inDoWhile--
            return r
        }
        val r = rebuildStmt(s)
        if (r is MirStmt.If) {
            val c = r.cond
            if (c is MirExpr.BooleanConst) {
                changed = true
                val n = MirStmt.Block(if (c.value) r.thenBody else r.elseBody)
                n.srcLine = r.srcLine
                n.srcFile = r.srcFile
                return n
            }
        }
        return r
    }

    private fun droppable(s: MirStmt): Boolean {
        if (s is MirStmt.Declare) {
            val l = s.local
            if (u.isParam(l)) return false
            val init = s.init
            // aliases are replaced everywhere, so their declarations go
            if (init != null && alias.containsKey(l.id)) {
                val r = resolve(l)
                if (r != null && r.type == l.type) return true
            }
            if (u.useCount(l) == 0 && u.assignCount(l) == 0 && inDoWhile == 0) {
                return init == null || isPureExpr(init)
            }
            return false
        }
        if (s is MirStmt.Eval) return isPureExpr(s.expr)
        return false
    }

    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        if (!prepared) prepare()
        var out: ArrayList<MirStmt>? = null
        var skip: ArrayList<MirStmt>? = null
        var merged: ArrayList<MirStmt.Declare>? = null
        var mergedFrom: ArrayList<MirStmt>? = null
        // `declare x; ...; x = v`: look a few statements ahead
        if (inDoWhile == 0) {
            for (i in l.indices) {
                val s = l[i]
                if (s !is MirStmt.Declare) continue
                val x = s.local
                if (u.isParam(x) || u.assignCount(x) != 1 || u.declared[x.id] != 1) continue
                if (!(s.init == null || isDefaultConst(s.init))) continue
                var j = i + 1
                while (j < l.size && j < i + 10 && !Mentions.of(l[j], x)) j++
                if (j >= l.size || j >= i + 10) continue
                val a = l[j]
                if (a is MirStmt.Assign && a.local === x && !Mentions.exprUses(a.value, x)) {
                    val d = MirStmt.Declare(x, a.value)
                    d.srcLine = a.srcLine
                    d.srcFile = a.srcFile
                    if (skip == null) { skip = ArrayList(); merged = ArrayList(); mergedFrom = ArrayList() }
                    skip.add(s)
                    merged!!.add(d)
                    mergedFrom!!.add(a)
                }
            }
        }
        for (i in l.indices) {
            val s = l[i]
            var drop = false
            var repl: MirStmt? = null
            if (skip != null) {
                for (k in skip.indices) if (skip[k] === s) drop = true
                for (k in mergedFrom!!.indices) if (mergedFrom[k] === s) repl = merged!![k]
            }
            if (!drop && repl == null && droppable(s)) drop = true
            var r: MirStmt? = null
            if (!drop) r = stmt(repl ?: s)
            if (drop || repl != null) changed = true
            if (out == null && (drop || r !== s)) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null && r != null) out.add(r)
        }
        return out ?: l
    }
}
