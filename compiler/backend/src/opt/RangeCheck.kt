package mini.backend

/*
 * Bounds-check hoisting by loop versioning. After [ArrayLen] the accessors of a primitive-array local carry the cached length.
 * An innermost `for (i in a until b)` loop whose body indexes such arrays with `i`, `i + x`, `x + i`, `i - x` or `x - i`
 * (`x` a constant or a local that the loop does not touch) is emitted twice, behind one test done before the loop:
 *
 *     if (a >= b || (lowest index >= 0 && highest index < len ...)) { copy with unchecked accesses } else { the original loop }
 *
 * The test is evaluated in Long arithmetic, so it is exact; when it holds every access of the copy is in bounds and
 * `mk_arrayu_get_*` / `mk_arrayu_set_*` (no length test) are used. When it fails the original loop runs and raises the
 * ArrayIndexOutOfBoundsException at the same iteration as before, after the same side effects. Object arrays keep their checks.
 */
internal object RangeCheck {
    private const val MAX_NODES = 220
    private val PRIM = setOf("int", "long", "double", "float", "byte", "short", "boolean", "char")

    fun run(module: MirModule, stats: IntArray): MirModule {
        var out: ArrayList<MirFunction>? = null
        val fs = module.functions
        for (i in fs.indices) {
            val f = fs[i]
            val g = if (f.strict) f else runFunction(f, stats)
            if (g !== f && out == null) {
                out = ArrayList(fs.size)
                for (k in 0 until i) out.add(fs[k])
            }
            if (out != null) out.add(g)
        }
        if (out == null) return module
        return MirModule(module.sourceName, out, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }

    private fun runFunction(f: MirFunction, stats: IntArray): MirFunction {
        val probe = Probe()
        probe.walk(f.body)
        if (!probe.any) return f
        val ids = IdScan(f)
        val v = Versioner(ids.maxLocal + 1, ids.maxLabel + 1)
        val body = v.stmts(f.body)
        if (v.count == 0) return f
        stats[0] += v.count
        return f.withBody(body)
    }

    /** Does the function have a ForRange loop with a checked access at all (cheap exit for most functions)? */
    private class Probe : MirVisitor() {
        var any = false
        override fun visit(e: MirExpr) {
            if (e is MirExpr.RuntimeCall && ArrayLen.lenAccessKind(e) != null) any = true
        }
    }

    private class Counter : MirVisitor() {
        var nodes = 0
        var loops = 0
        override fun visit(e: MirExpr) { nodes++ }
        override fun stmt(s: MirStmt): MirStmt {
            nodes++
            if (s is MirStmt.While || s is MirStmt.Loop || s is MirStmt.DoWhile || s is MirStmt.ForRange) loops++
            return super.stmt(s)
        }
    }

    /** Locals assigned or declared and labels declared inside a statement list. */
    private class Inner : MirVisitor() {
        val declared = ArrayList<MirLocal>()
        val assigned = HashSet<Int>()
        val labels = ArrayList<MirLabel>()
        override fun visit(e: MirExpr) {}
        override fun stmt(s: MirStmt): MirStmt {
            when (s) {
                is MirStmt.Declare -> declared.add(s.local)
                is MirStmt.Assign -> assigned.add(s.local.id)
                is MirStmt.ForRange -> { declared.add(s.variable); labels.add(s.label) }
                is MirStmt.While -> labels.add(s.label)
                is MirStmt.Loop -> labels.add(s.label)
                is MirStmt.DoWhile -> labels.add(s.label)
                else -> {}
            }
            return super.stmt(s)
        }
    }

    /** A pure Int expression made of constants, locals, + - * : re-evaluating it before the loop gives the loop's own value. */
    private fun pure(e: MirExpr): Boolean = when (e) {
        is MirExpr.IntConst, is MirExpr.Local -> e.type === TInt
        is MirExpr.Binary -> (e.op == BinaryOp.Add || e.op == BinaryOp.Sub || e.op == BinaryOp.Mul) && e.type === TInt && pure(e.left) && pure(e.right)
        else -> false
    }

    private fun copyPure(e: MirExpr): MirExpr = when (e) {
        is MirExpr.IntConst -> MirExpr.IntConst(e.value)
        is MirExpr.Local -> MirExpr.Local(e.local)
        is MirExpr.Binary -> MirExpr.Binary(e.op, copyPure(e.left), copyPure(e.right))
        else -> e
    }

    private fun lng(e: MirExpr): MirExpr = MirExpr.Convert(copyPure(e), TLong)
    private fun lc(v: Long): MirExpr = MirExpr.LongConst(v)
    private fun bin(op: BinaryOp, a: MirExpr, b: MirExpr): MirExpr = MirExpr.Binary(op, a, b)

    /** One access index form: kind 0 `i + x` (x may be null: plain `i`), 1 `i - x`, 2 `x - i`. */
    private class Form(val kind: Int, val x: MirExpr?)

    /** Is an index operand constant over the whole loop? */
    private abstract class Inv {
        abstract fun ok(x: MirExpr): Boolean
    }

    /** For the original loop: a constant, or an Int local that is neither declared nor assigned in the loop body. */
    private class OrigInv(val v: MirLocal, val declaredIds: Set<Int>, val assigned: Set<Int>) : Inv() {
        override fun ok(x: MirExpr): Boolean =
            (x is MirExpr.IntConst) || (x is MirExpr.Local && x.type === TInt && x.local !== v && x.local.id !in declaredIds && x.local.id !in assigned)
    }

    /** The same for the renamed copy (the locals declared inside the loop have new ids there). */
    private class CopyInv(val copier: LoopCopier, val assigned: Set<Int>) : Inv() {
        override fun ok(x: MirExpr): Boolean =
            (x is MirExpr.IntConst) || (x is MirExpr.Local && x.type === TInt && !copier.isInner(x.local) && x.local.id !in assigned)
    }

    private fun formOf(idx: MirExpr, v: MirLocal, invariant: Inv): Form? {
        if (idx is MirExpr.Local) return if (idx.local === v) Form(0, null) else null
        if (idx !is MirExpr.Binary || idx.type !== TInt) return null
        val l = idx.left
        val r = idx.right
        if (idx.op == BinaryOp.Add) {
            if (l is MirExpr.Local && l.local === v && invariant.ok(r)) return Form(0, r)
            if (r is MirExpr.Local && r.local === v && invariant.ok(l)) return Form(0, l)
        } else if (idx.op == BinaryOp.Sub) {
            if (l is MirExpr.Local && l.local === v && invariant.ok(r)) return Form(1, r)
            if (r is MirExpr.Local && r.local === v && invariant.ok(l)) return Form(2, l)
        }
        return null
    }

    private fun xKey(x: MirExpr?): String = when (x) {
        null -> "0"
        is MirExpr.IntConst -> "c" + x.value
        is MirExpr.Local -> "l" + x.local.id
        else -> "?"
    }

    private class Versioner(var nextLocal: Int, var nextLabel: Int) : MirTransform() {
        var count = 0

        override fun stmt(s: MirStmt): MirStmt {
            val r = rebuildStmt(s)
            if (r is MirStmt.ForRange) {
                val g = version(r)
                if (g != null) return keep(s, g)
            }
            return r
        }

        fun version(loop: MirStmt.ForRange): MirStmt? {
            if (loop.variable.type !== TInt || !loop.ascending) return null
            val st = loop.step
            if (st != null && !(st is MirExpr.IntConst && st.value == 1)) return null
            if (!pure(loop.start) || !pure(loop.end)) return null
            val cnt = Counter()
            cnt.walk(loop.body)
            if (cnt.loops > 0 || cnt.nodes > MAX_NODES) return null
            val inner = Inner()
            inner.walk(loop.body)
            val declaredIds = HashSet<Int>()
            for (l in inner.declared) declaredIds.add(l.id)
            val v = loop.variable
            val invariant = OrigInv(v, declaredIds, inner.assigned)
            // the accesses that can lose their test
            val find = Finder(v, invariant, declaredIds)
            find.walk(loop.body)
            if (find.forms.isEmpty()) return null
            // the guard
            val lo = loop.start
            val hi = loop.end
            val hiEx = if (loop.inclusive) bin(BinaryOp.Add, lng(hi), lc(1)) else lng(hi)
            val last = bin(BinaryOp.Sub, hiEx, lc(1))
            var ok: MirExpr? = null
            for (fm in find.forms.values) {
                val (mn, mx) = when (fm.form.kind) {
                    0 -> if (fm.form.x == null) Pair(lng(lo), last) else Pair(bin(BinaryOp.Add, lng(lo), lng(fm.form.x)), bin(BinaryOp.Add, last, lng(fm.form.x)))
                    1 -> Pair(bin(BinaryOp.Sub, lng(lo), lng(fm.form.x!!)), bin(BinaryOp.Sub, last, lng(fm.form.x)))
                    else -> Pair(bin(BinaryOp.Sub, lng(fm.form.x!!), last), bin(BinaryOp.Sub, lng(fm.form.x), lng(lo)))
                }
                val c1 = bin(BinaryOp.Ge, mn, lc(0))
                val c2 = bin(BinaryOp.Lt, mx, MirExpr.Convert(MirExpr.Local(fm.len), TLong))
                val c = bin(BinaryOp.AndAnd, c1, c2)
                ok = if (ok == null) c else bin(BinaryOp.AndAnd, ok, c)
            }
            val empty = bin(if (loop.inclusive) BinaryOp.Gt else BinaryOp.Ge, lng(lo), lng(hi))
            val guard = bin(BinaryOp.OrOr, empty, ok!!)
            // the unchecked copy, with fresh locals and labels for everything declared inside the loop
            val copier = LoopCopier(nextLocal, nextLabel)
            copier.prepare(loop, inner)
            val copy = copier.stmt(loop) as MirStmt.ForRange
            nextLocal = copier.nextLocal
            nextLabel = copier.nextLabel
            val rw = Unchecked(copier.mapped(loop.variable), copier, inner.assigned)
            val fast = rw.stmt(copy)
            count++
            return MirStmt.If(guard, listOf(fast), listOf(loop))
        }
    }

    private class Entry(val form: Form, val len: MirLocal)

    /** Collects the distinct (length local, index form) pairs of the accesses that can be unchecked. */
    private class Finder(val v: MirLocal, val invariant: Inv, val declaredIds: Set<Int>) : MirVisitor() {
        val forms = LinkedHashMap<String, Entry>()
        override fun visit(e: MirExpr) {
            if (e !is MirExpr.RuntimeCall) return
            val k = ArrayLen.lenAccessKind(e) ?: return
            if (k !in PRIM) return
            val len = e.args[e.args.size - 1]
            if (len !is MirExpr.Local || len.local.id in declaredIds) return
            val a = e.args[0]
            if (a !is MirExpr.Local || a.local.id in declaredIds) return
            val fm = formOf(e.args[1], v, invariant) ?: return
            val key = len.local.id.toString() + ":" + fm.kind + ":" + xKey(fm.x)
            if (!forms.containsKey(key)) forms[key] = Entry(fm, len.local)
        }
    }

    /** Copies a loop with fresh nodes; locals and labels declared inside it get new ids, everything else is kept. */
    private class LoopCopier(var nextLocal: Int, var nextLabel: Int) : MirTransform() {
        private val lf = ArrayList<MirLocal>()
        private val lt = ArrayList<MirLocal>()
        private val bf = ArrayList<MirLabel>()
        private val bt = ArrayList<MirLabel>()

        fun prepare(loop: MirStmt.ForRange, inner: Inner) {
            addLocal(loop.variable)
            addLabel(loop.label)
            for (l in inner.declared) addLocal(l)
            for (l in inner.labels) addLabel(l)
        }

        private fun addLocal(l: MirLocal) {
            for (x in lf) if (x === l) return
            lf.add(l)
            lt.add(MirLocal(nextLocal++, l.name, l.type))
        }

        private fun addLabel(l: MirLabel) {
            for (x in bf) if (x === l) return
            bf.add(l)
            bt.add(MirLabel(nextLabel++))
        }

        fun isInner(l: MirLocal): Boolean {
            for (x in lt) if (x === l) return true
            return false
        }

        fun mapped(l: MirLocal): MirLocal {
            for (i in lf.indices) if (lf[i] === l) return lt[i]
            return l
        }

        private fun lab(l: MirLabel): MirLabel {
            for (i in bf.indices) if (bf[i] === l) return bt[i]
            return l
        }

        override fun expr(e: MirExpr): MirExpr {
            if (e is MirExpr.Local) return MirExpr.Local(mapped(e.local))
            val r = rebuildExpr(e)
            return if (r === e && isLeaf(e)) cloneLeaf(e) else r
        }

        override fun stmt(s: MirStmt): MirStmt {
            val r = rebuildStmt(s)
            val n: MirStmt = when (r) {
                is MirStmt.Declare -> MirStmt.Declare(mapped(r.local), r.init)
                is MirStmt.Assign -> MirStmt.Assign(mapped(r.local), r.value)
                is MirStmt.While -> MirStmt.While(lab(r.label), r.cond, r.body)
                is MirStmt.Loop -> MirStmt.Loop(lab(r.label), r.body)
                is MirStmt.DoWhile -> MirStmt.DoWhile(lab(r.label), r.body, r.condStmts, r.cond)
                is MirStmt.ForRange -> MirStmt.ForRange(lab(r.label), mapped(r.variable), r.start, r.end, r.step, r.ascending, r.inclusive, r.body)
                is MirStmt.Break -> MirStmt.Break(lab(r.label))
                is MirStmt.Continue -> MirStmt.Continue(lab(r.label))
                is MirStmt.Panic -> MirStmt.Panic(r.message)
                else -> r
            }
            if (n !== r) return keep(s, n)
            return if (r === s) keep(s, copyShell(r)) else r
        }

        /** A statement whose operands and bodies were all shared still has to be a new node (node identity keys later analyses). */
        private fun copyShell(s: MirStmt): MirStmt = when (s) {
            is MirStmt.If -> MirStmt.If(s.cond, s.thenBody, s.elseBody)
            is MirStmt.Block -> MirStmt.Block(s.body)
            is MirStmt.Return -> MirStmt.Return(s.value)
            is MirStmt.Eval -> MirStmt.Eval(s.expr)
            is MirStmt.Try -> MirStmt.Try(s.body, s.catches)
            else -> s
        }
    }

    /** Turns the accesses of the copy whose index form is covered by the guard into unchecked ones. */
    private class Unchecked(val v: MirLocal, val copier: LoopCopier, val assigned: Set<Int>) : MirTransform() {
        val invariant = CopyInv(copier, assigned)

        override fun expr(e: MirExpr): MirExpr {
            val r = rebuildExpr(e)
            if (r !is MirExpr.RuntimeCall) return r
            val k = ArrayLen.lenAccessKind(r) ?: return r
            if (k !in PRIM) return r
            val a0 = r.args[0]
            val len = r.args[r.args.size - 1]
            if (a0 !is MirExpr.Local || copier.isInner(a0.local) || len !is MirExpr.Local || copier.isInner(len.local)) return r
            if (formOf(r.args[1], v, invariant) == null) return r
            val args = ArrayList<MirExpr>(r.args.size - 1)
            for (i in 0 until r.args.size - 1) args.add(r.args[i])
            return MirExpr.RuntimeCall(r.cName.replace("mk_array_", "mk_arrayu_"), args, r.type)
        }
    }
}
