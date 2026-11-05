package mini.backend

/*
 * Local clean-up after inlining and the other opt passes: copy propagation of single-definition locals that merely alias another
 * never-reassigned local or a constant, removal of unused pure declarations and of `if` on constants.
 */
internal object Cleanup {
    fun run(body: List<MirStmt>, f: MirFunction): List<MirStmt> {
        var cur = body
        var round = 0
        while (round < 4) {
            val u = UseScan(f)
            u.walk(cur)
            val next = CleanupPass(u, f).stmts(cur)
            if (next === cur) break
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

private class Replace(val from: MirLocal, val to: MirExpr) : MirTransform() {
    override fun expr(e: MirExpr): MirExpr {
        if (e is MirExpr.Local && e.local === from) return cloneLeaf(to)
        return rebuildExpr(e)
    }
}

private class CleanupPass(val u: UseScan, val f: MirFunction) : MirTransform() {
    /** Inside the body of a do-while the declarations are also visible in the condition: leave them alone. */
    var inDoWhile = 0

    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.DoWhile) {
            inDoWhile++
            val r = rebuildStmt(s)
            inDoWhile--
            return r
        }
        return rebuildStmt(s)
    }

    /** Pending replacements discovered while scanning a list: applied to the rest of the list (and nested bodies). */
    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var cur = l
        var i = 0
        var out: ArrayList<MirStmt>? = null
        while (i < cur.size) {
            val s = cur[i]
            if (s is MirStmt.Declare && s.init == null && inDoWhile == 0 && !u.isParam(s.local) && u.useCount(s.local) == 0 && u.assignCount(s.local) == 0) {
                val head = ArrayList<MirStmt>()
                for (k in 0 until cur.size) if (k != i) head.add(cur[k])
                cur = head
                out = ArrayList(cur)
                continue
            }
            if (s is MirStmt.Declare && s.init != null) {
                val init = s.init
                val l0 = s.local
                if (inDoWhile == 0 && u.assignCount(l0) == 0 && u.declared[l0.id] == 1 && !u.isParam(l0)) {
                    var drop = false
                    if (u.useCount(l0) == 0 && isPureExpr(init)) drop = true
                    else if (aliasable(init, l0)) {
                        // replace every use in the rest of this list (uses cannot lie outside it: the local is scoped to it)
                        val rep = Replace(l0, init)
                        val rest = ArrayList<MirStmt>()
                        for (k in i + 1 until cur.size) rest.add(rep.stmt(cur[k]))
                        val head = ArrayList<MirStmt>()
                        for (k in 0 until i) head.add(cur[k])
                        head.addAll(rest)
                        cur = head
                        out = ArrayList(cur)
                        continue
                    }
                    if (drop) {
                        val head = ArrayList<MirStmt>()
                        for (k in 0 until cur.size) if (k != i) head.add(cur[k])
                        cur = head
                        out = ArrayList(cur)
                        continue
                    }
                }
            }
            if (s is MirStmt.Eval && isPureExpr(s.expr)) {
                val head = ArrayList<MirStmt>()
                for (k in 0 until cur.size) if (k != i) head.add(cur[k])
                cur = head
                out = ArrayList(cur)
                continue
            }
            i++
        }
        // nested bodies
        var res: ArrayList<MirStmt>? = null
        for (k in cur.indices) {
            val s = cur[k]
            val r = foldIf(stmt(s))
            if (res == null && r !== s) {
                res = ArrayList(cur.size)
                for (j in 0 until k) res.add(cur[j])
            }
            if (res != null) res.add(r)
        }
        if (res != null) return res
        return if (out != null) cur else l
    }

    fun foldIf(s: MirStmt): MirStmt {
        if (s is MirStmt.If) {
            val c = s.cond
            if (c is MirExpr.BooleanConst) {
                val b = if (c.value) s.thenBody else s.elseBody
                val n = MirStmt.Block(b)
                n.srcLine = s.srcLine
                n.srcFile = s.srcFile
                return n
            }
        }
        return s
    }

    /** [init] can stand for the local everywhere: a constant, or a never-reassigned local of the same type. */
    fun aliasable(init: MirExpr, l0: MirLocal): Boolean {
        if (init is MirExpr.IntConst || init is MirExpr.LongConst || init is MirExpr.DoubleConst || init is MirExpr.BooleanConst || init is MirExpr.CharConst) {
            return init.type == l0.type
        }
        if (init is MirExpr.Local) {
            val x = init.local
            if (x === l0) return false
            if (u.assignCount(x) != 0) return false
            if (x.type != l0.type) return false
            return true
        }
        return false
    }
}
