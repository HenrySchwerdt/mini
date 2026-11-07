package mini.backend

/*
 * Scalar replacement of small object arrays that never leave their function.
 *
 * `val a = arrayOf(o0, o1, ..., on)` (a `mk_array_new_of` of a constant length followed by one `mk_array_set_obj` per slot, the
 * elements being locals that are never reassigned) is replaced by its element locals when, after
 *  - inlining every call that takes the array (whatever its size, up to a budget),
 *  - fully unrolling the counting loops with constant bounds that index it (nested, triangular ones too: `for (j in i + 1 until 5)`),
 *  - folding the constant index arithmetic,
 * every use of the array is `a[<constant>]` or `a.size`. Then each `a[k]` is the k-th element local, the array is never allocated and
 * the elements (typically fresh objects) lose their last use that was not a field access, so scalar replacement ([Sroa]) can turn them
 * into plain locals. The whole transformation is tried on a copy of the function body: when a single other use is left (the array is
 * stored, returned, passed to a call that stays a call, indexed by a non-constant, a loop is too long to unroll ...) the function is
 * left exactly as it was. Constant indices that are in bounds cannot fail, so no exception is lost.
 */
private class SMentions(val ids: HashSet<Int>) : MirVisitor() {
    var found = false
    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local && ids.contains(e.local.id)) found = true
    }
}

private fun mentionsAny(l: List<MirStmt>, ids: HashSet<Int>): Boolean {
    val v = SMentions(ids)
    v.walk(l)
    return v.found
}

/** Locals and labels declared and used in a statement list. */
private class DeclScan : MirVisitor() {
    val declLocals = HashSet<Int>()
    val usedLocals = ArrayList<MirLocal>()
    val seen = HashSet<Int>()
    val assigned = HashSet<Int>()
    val declLabels = HashSet<Int>()
    val usedLabels = ArrayList<MirLabel>()
    val seenLabels = HashSet<Int>()

    private fun note(l: MirLocal) {
        if (seen.add(l.id)) usedLocals.add(l)
    }

    private fun noteLabel(l: MirLabel) {
        if (seenLabels.add(l.id)) usedLabels.add(l)
    }

    override fun visit(e: MirExpr) {
        if (e is MirExpr.Local) note(e.local)
    }

    override fun stmt(s: MirStmt): MirStmt {
        when (s) {
            is MirStmt.Declare -> { declLocals.add(s.local.id); note(s.local) }
            is MirStmt.Assign -> { assigned.add(s.local.id); note(s.local) }
            is MirStmt.ForRange -> { declLocals.add(s.variable.id); declLabels.add(s.label.id); noteLabel(s.label) }
            is MirStmt.While -> { declLabels.add(s.label.id); noteLabel(s.label) }
            is MirStmt.Loop -> { declLabels.add(s.label.id); noteLabel(s.label) }
            is MirStmt.DoWhile -> { declLabels.add(s.label.id); noteLabel(s.label) }
            is MirStmt.Break -> noteLabel(s.label)
            is MirStmt.Continue -> noteLabel(s.label)
            else -> {}
        }
        return super.stmt(s)
    }
}

/** Folds `+ - *` and comparisons of Int constants. */
private class ConstFold : MirTransform() {
    var changed = false
    override fun expr(e: MirExpr): MirExpr {
        val r = rebuildExpr(e)
        if (r is MirExpr.Binary) {
            val a = r.left
            val b = r.right
            if (a is MirExpr.IntConst && b is MirExpr.IntConst) {
                val x = a.value
                val y = b.value
                val out: MirExpr? = when (r.op) {
                    BinaryOp.Add -> MirExpr.IntConst(x + y)
                    BinaryOp.Sub -> MirExpr.IntConst(x - y)
                    BinaryOp.Mul -> MirExpr.IntConst(x * y)
                    BinaryOp.Eq -> MirExpr.BooleanConst(x == y)
                    BinaryOp.Ne -> MirExpr.BooleanConst(x != y)
                    BinaryOp.Lt -> MirExpr.BooleanConst(x < y)
                    BinaryOp.Le -> MirExpr.BooleanConst(x <= y)
                    BinaryOp.Gt -> MirExpr.BooleanConst(x > y)
                    BinaryOp.Ge -> MirExpr.BooleanConst(x >= y)
                    else -> null
                }
                if (out != null) {
                    changed = true
                    return out
                }
            }
        }
        return r
    }
}

/** Inlines the direct calls (as a whole statement, or the initialiser / right side of a local) that take the array. */
private class ArrayInliner(val st: InlineState, val caller: MirFunction, val ex: Expander, val ids: HashSet<Int>) : MirTransform() {
    var count = 0

    private fun takesArray(c: MirExpr.Call): Boolean {
        for (a in c.args) if (a is MirExpr.Local && ids.contains(a.local.id)) return true
        return false
    }

    private fun infoFor(e: MirExpr?): InlineInfo? {
        if (e !is MirExpr.Call || !takesArray(e)) return null
        val fn = st.done[e.cName] ?: st.byName[e.cName] ?: return null
        if (fn.cName == caller.cName || fn.strict || fn.noHeapEffects || fn.returnType === TNothing || fn.cName == Mangling.MAIN) return null
        if (fn.params.size != e.args.size) return null
        val calls = CallCollector()
        calls.walk(fn.body)
        if (calls.names.contains(fn.cName)) return null
        val size = NodeSize.of(fn.body)
        if (size > ArraySroa.MAX_CALLEE) return null
        if (!st.tailReturnsOnly(fn.body, fn.returnType === TUnit)) return null
        val assigned = AssignedParams(fn.params)
        assigned.walk(fn.body)
        return InlineInfo(fn, assigned.flags, size)
    }

    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            val r = stmt(s)
            var x: List<MirStmt>? = null
            if (r is MirStmt.Declare) {
                val info = infoFor(r.init)
                if (info != null) x = ex.stamp(r, ex.expandCall(r.init as MirExpr.Call, info, 1, r.local))
            } else if (r is MirStmt.Assign) {
                val info = infoFor(r.value)
                if (info != null) x = ex.stamp(r, ex.expandCall(r.value as MirExpr.Call, info, 2, r.local))
            } else if (r is MirStmt.Eval) {
                val info = infoFor(r.expr)
                if (info != null) x = ex.stamp(r, ex.expandCall(r.expr as MirExpr.Call, info, 0, null))
            }
            if (x != null) count++
            if (out == null && (x != null || r !== s)) {
                out = ArrayList(l.size + 4)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null) {
                if (x != null) out.addAll(x) else out.add(r)
            }
        }
        return out ?: l
    }
}

/** Unrolls the counting loops with constant bounds and a short trip count that mention the array. */
private class Unroller(val ids: HashSet<Int>, var nextLocal: Int, var nextLabel: Int) : MirTransform() {
    var count = 0

    override fun stmt(s: MirStmt): MirStmt {
        if (s is MirStmt.ForRange) {
            val r = unroll(s)
            if (r != null) return r
        }
        return rebuildStmt(s)
    }

    private fun unroll(f: MirStmt.ForRange): MirStmt? {
        val sc = f.start
        val ec = f.end
        if (sc !is MirExpr.IntConst || ec !is MirExpr.IntConst) return null
        if (f.variable.type != TInt || !f.ascending) return null
        var step = 1L
        val se = f.step
        if (se != null) {
            if (se !is MirExpr.IntConst || se.value <= 0) return null
            step = se.value.toLong()
        }
        val vals = ArrayList<Int>()
        var v = sc.value.toLong()
        val end = ec.value.toLong()
        while (if (f.inclusive) v <= end else v < end) {
            if (vals.size >= ArraySroa.MAX_TRIPS) return null
            vals.add(v.toInt())
            v += step
        }
        if (!mentionsAny(f.body, ids)) return null
        val bodySize = NodeSize.of(f.body)
        if (bodySize * vals.size > ArraySroa.MAX_UNROLLED) return null
        val scan = DeclScan()
        scan.walk(f.body)
        if (scan.assigned.contains(f.variable.id)) return null
        for (l in scan.usedLabels) if (l.id == f.label.id) return null
        val blocks = ArrayList<MirStmt>()
        for (k in vals.indices) {
            val repl = ArrayList<MirExpr>()
            repl.add(MirExpr.IntConst(vals[k]))
            val params = ArrayList<MirLocal>()
            params.add(f.variable)
            val copier = BodyCopier(params, repl, nextLocal, nextLabel)
            // locals and labels that are declared outside the body stay as they are
            for (l in scan.usedLocals) {
                if (l === f.variable || scan.declLocals.contains(l.id)) continue
                copier.localsFrom.add(l)
                copier.localsTo.add(l)
            }
            for (l in scan.usedLabels) {
                if (scan.declLabels.contains(l.id)) continue
                copier.labelsFrom.add(l)
                copier.labelsTo.add(l)
            }
            val copied = copier.stmts(f.body)
            nextLocal = copier.nextLocal
            nextLabel = copier.nextLabel
            val b = MirStmt.Block(copied)
            b.srcLine = f.srcLine
            b.srcFile = f.srcFile
            blocks.add(b)
        }
        count++
        val whole = MirStmt.Block(blocks)
        whole.srcLine = f.srcLine
        whole.srcFile = f.srcFile
        return whole
    }
}

/** `a[k]` becomes the k-th element, `a.size` the constant length; `(T)(Any)x` with `x: T` becomes `x`. */
private class ArrayRewriter(val ids: HashSet<Int>, val n: Int, val elems: List<MirLocal?>, val u: UseScan, val top: HashSet<Int>) : MirTransform() {
    private fun element(k: Int, type: MirType): MirExpr {
        val el = elems[k]!!
        var base: MirExpr = MirExpr.Local(el)
        val init = u.inits[el.id]
        if (init is MirExpr.Cast && init.mode == MirExpr.CastMode.Unchecked) {
            val o = init.operand
            if (o is MirExpr.Local && top.contains(o.local.id) && u.assignCount(o.local) == 0 && u.declared[o.local.id] == 1) base = MirExpr.Local(o.local)
        }
        return if (base.type == type) base else MirExpr.Cast(base, type, MirExpr.CastMode.Unchecked)
    }

    override fun expr(e: MirExpr): MirExpr {
        val r = rebuildExpr(e)
        if (r is MirExpr.RuntimeCall) {
            val a0 = if (r.args.isNotEmpty()) r.args[0] else null
            if (a0 is MirExpr.Local && ids.contains(a0.local.id)) {
                if (r.cName == "mk_array_get_obj" && r.args.size >= 2) {
                    val i = r.args[1]
                    if (i is MirExpr.IntConst && i.value >= 0 && i.value < n) return element(i.value, r.type)
                } else if (r.cName == "mk_array_length" && r.args.size == 1) {
                    return MirExpr.IntConst(n)
                }
            }
        }
        if (r is MirExpr.Cast && r.mode == MirExpr.CastMode.Unchecked) {
            val o = r.operand
            if (o is MirExpr.Cast && o.mode == MirExpr.CastMode.Unchecked && o.operand.type == r.to) return o.operand
        }
        return r
    }
}

/** `declare t = (Any)o` of a never reassigned `o`, used as `(Body)t`: the uses become `o` itself, the declaration goes. */
private class CastAlias(val u: UseScan) : MirTransform() {
    val map = HashMap<Int, MirExpr.Cast>()
    var changed = false

    init {
        for ((id, init) in u.inits) {
            if (init !is MirExpr.Cast || init.mode != MirExpr.CastMode.Unchecked) continue
            val o = init.operand
            if (o !is MirExpr.Local || o.local.id == id) continue
            if (u.declared[id] != 1 || u.assigns[id] != null || u.assigns[o.local.id] != null) continue
            map[id] = init
        }
    }

    /** The expression for the alias defined by [c]; aliases of aliases are followed (their declarations go too). */
    private fun build(c: MirExpr.Cast, depth: Int): MirExpr {
        val o = (c.operand as MirExpr.Local).local
        val inner = map[o.id]
        var base: MirExpr = MirExpr.Local(o)
        if (inner != null && inner.to == o.type && depth < 50) base = build(inner, depth + 1)
        if (base is MirExpr.Cast && base.mode == MirExpr.CastMode.Unchecked && base.operand.type == c.to) return base.operand
        return MirExpr.Cast(base, c.to, c.mode)
    }

    override fun expr(e: MirExpr): MirExpr {
        if (e is MirExpr.Local) {
            val c = map[e.local.id]
            if (c != null && c.to == e.local.type) {
                changed = true
                return build(c, 0)
            }
            return e
        }
        val r = rebuildExpr(e)
        if (r is MirExpr.Cast && r.mode == MirExpr.CastMode.Unchecked) {
            val o = r.operand
            if (o is MirExpr.Cast && o.mode == MirExpr.CastMode.Unchecked && o.operand.type == r.to) {
                changed = true
                return o.operand
            }
        }
        return r
    }

    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            val drop = s is MirStmt.Declare && map.containsKey(s.local.id) && map[s.local.id]!!.to == s.local.type
            val r = if (drop) s else stmt(s)
            if (out == null && (drop || r !== s)) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null && !drop) out.add(r)
        }
        return out ?: l
    }
}

/** Removes the declarations of the array locals anywhere and its initial `set` statements at the top level. */
private class ArrayDropper(val ids: HashSet<Int>, val top: List<MirStmt>) : MirTransform() {
    private fun isSet(s: MirStmt): Boolean {
        if (s !is MirStmt.Eval) return false
        val c = s.expr
        if (c !is MirExpr.RuntimeCall || c.cName != "mk_array_set_obj" || c.args.isEmpty()) return false
        val a0 = c.args[0]
        return a0 is MirExpr.Local && ids.contains(a0.local.id)
    }

    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            var drop = s is MirStmt.Declare && ids.contains(s.local.id)
            if (!drop && l === top && isSet(s)) drop = true
            val r = if (drop) s else stmt(s)
            if (out == null && (drop || r !== s)) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null && !drop) out.add(r)
        }
        return out ?: l
    }
}

internal class ArraySroa(val st: InlineState, val caller: MirFunction) {
    companion object {
        const val MAX_LENGTH = 16
        const val MAX_TRIPS = 16
        const val MAX_CALLEE = 800
        const val MAX_UNROLLED = 3000
        const val MAX_BODY = 9000
        const val ROUNDS = 8

        /** Cheap filter: a top-level `mk_array_new_of` of a small constant length. */
        fun worthTrying(f: MirFunction): Boolean {
            if (f.strict) return false
            for (s in f.body) {
                if (s !is MirStmt.Declare) continue
                val i = s.init
                if (i is MirExpr.RuntimeCall && i.cName == "mk_array_new_of" && i.args.isNotEmpty()) return true
            }
            return false
        }
    }

    /** The new body, or null when nothing was replaced. */
    fun run(): List<MirStmt>? {
        if (caller.strict) return null
        var cur = caller.body
        var any = false
        val cands = ArrayList<MirLocal>()
        for (s in cur) {
            if (s !is MirStmt.Declare) continue
            val i = s.init
            if (i !is MirExpr.RuntimeCall || i.cName != "mk_array_new_of" || i.args.isEmpty()) continue
            val n = i.args[0]
            if (n is MirExpr.IntConst && n.value >= 1 && n.value <= MAX_LENGTH) cands.add(s.local)
        }
        for (a in cands) {
            val r = tryArray(cur, a)
            if (r != null) {
                cur = r
                any = true
                st.stats.arraysReplaced++
            }
        }
        return if (any) cur else null
    }

    private fun tryArray(body: List<MirStmt>, arr: MirLocal): List<MirStmt>? {
        var declIdx = -1
        for (k in body.indices) {
            val s = body[k]
            if (s is MirStmt.Declare && s.local === arr) declIdx = k
        }
        if (declIdx < 0) return null
        val u0 = UseScan(caller)
        u0.walk(body)
        if (u0.declared[arr.id] != 1 || u0.assignCount(arr) != 0) return null
        val init = (body[declIdx] as MirStmt.Declare).init as MirExpr.RuntimeCall
        val n = (init.args[0] as MirExpr.IntConst).value
        val ids = HashSet<Int>()
        ids.add(arr.id)
        val top = HashSet<Int>()
        for (p in caller.params) top.add(p.id)
        for (k in 0 until declIdx) {
            val s = body[k]
            if (s is MirStmt.Declare) top.add(s.local.id)
        }
        val elems = ArrayList<MirLocal?>()
        for (k in 0 until n) elems.add(null)
        var sets = 0
        var idx = declIdx + 1
        while (idx < body.size && sets < n) {
            val s = body[idx]
            idx++
            var handled = false
            if (s is MirStmt.Eval) {
                val c = s.expr
                if (c is MirExpr.RuntimeCall && c.cName == "mk_array_set_obj" && c.args.size == 3) {
                    val a0 = c.args[0]
                    val k = c.args[1]
                    val v = c.args[2]
                    if (a0 is MirExpr.Local && ids.contains(a0.local.id) && k is MirExpr.IntConst && v is MirExpr.Local) {
                        if (k.value < 0 || k.value >= n || elems[k.value] != null) return null
                        val el = v.local
                        if (!top.contains(el.id) || u0.declared[el.id] != 1 || u0.assignCount(el) != 0) return null
                        elems[k.value] = el
                        sets++
                        handled = true
                    }
                }
            }
            if (!handled) {
                if (s is MirStmt.Declare) top.add(s.local.id)
                if (mentionsAny(listOf(s), ids)) return null
            }
        }
        if (sets != n) return null
        var work = body
        var round = 0
        while (round < ROUNDS) {
            round++
            var progress = false
            val fold = ConstFold()
            val w1 = fold.stmts(work)
            if (fold.changed) { work = w1; progress = true }
            val w2 = Cleanup.run(work, caller)
            if (w2 !== work) { work = w2; progress = true }
            // aliases of the array
            val u = UseScan(caller)
            u.walk(work)
            var grew = true
            while (grew) {
                grew = false
                for ((id, i0) in u.inits) {
                    if (i0 is MirExpr.Local && ids.contains(i0.local.id) && !ids.contains(id) && u.declared[id] == 1 && u.assigns[id] == null) {
                        ids.add(id)
                        grew = true
                    }
                }
            }
            val fnNow = caller.withBody(work)
            val ex = Expander(st, fnNow)
            val ai = ArrayInliner(st, caller, ex, ids)
            val w3 = ai.stmts(work)
            if (ai.count > 0) { work = w3; progress = true }
            val un = Unroller(ids, ex.nextLocal, ex.nextLabel)
            val w4 = un.stmts(work)
            if (un.count > 0) { work = w4; progress = true }
            if (!progress) break
            if (NodeSize.of(work) > MAX_BODY) return null
        }
        val u = UseScan(caller)
        u.walk(work)
        val rw = ArrayRewriter(ids, n, elems, u, top)
        var out = rw.stmts(work)
        val dropper = ArrayDropper(ids, out)
        out = dropper.stmts(out)
        if (mentionsAny(out, ids)) return null
        var k = 0
        while (k < 3) {
            k++
            val cu = UseScan(caller)
            cu.walk(out)
            val ca = CastAlias(cu)
            val o2 = ca.stmts(out)
            if (!ca.changed) break
            out = o2
        }
        return Cleanup.run(out, caller)
    }
}
