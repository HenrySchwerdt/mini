package mini.backend.memory

/**
 * Entry point of the Strict-mode ownership checker (docs/memory-model.md).
 *
 * Pipeline per function: MemIr -> CFG ([CfgBuilder]) -> forward dataflow to a fixpoint ([Interp] transfer
 * functions, join = union of state sets) -> one reporting pass over the stable block entry states.
 * Escaping lambdas are analysed as separate units. Managed functions only get the boundary check (M0110).
 */
object MemoryChecker {
    fun check(module: MModule): List<Diag> {
        val all = LinkedHashMap<String, Diag>()
        computeThrowSummaries(module)
        // pass 1 only collects summaries (storesParams); pass 2 reports
        for (f in module.functions) if (f.fn.mode == MemMode.Strict) checkFunction(f) { }
        for (f in module.functions) {
            val sink: (Diag) -> Unit = { d ->
                d.file = f.file
                all.putIfAbsent(d.key, d)
            }
            if (f.fn.mode == MemMode.Strict) checkFunction(f, sink) else checkManaged(f, sink)
        }
        // one cause, one message: a leak report for a slot that already has a use/free/move error is a follow-up
        val dirty = all.values.filter { it.code != "M0102" && it.slot >= 0 }.map { it.slot }.toSet()
        // a loan of an arena value that leaves the arena is one cause: M0105 at that position, not also M0109
        val escapes = all.values.filter { it.code == "M0105" }.map { it.pos }.toSet()
        return all.values
            .filter { !(it.code == "M0102" && it.slot in dirty) && !(it.code == "M0109" && it.pos in escapes) && it.pos.line > 0 }
            .sortedWith(compareBy({ it.file }, { it.pos.line }, { it.pos.col }, { it.code }))
    }

    // ---- exception summaries (5.6) -------------------------------------------------------------

    private fun computeThrowSummaries(m: MModule) {
        var changed = true
        while (changed) {
            changed = false
            for (f in m.functions) {
                if (f.fn.mayThrow) continue
                if (containsThrow(f.body)) { f.fn.mayThrow = true; changed = true }
            }
        }
    }

    private fun containsThrow(body: List<MStmt>): Boolean {
        var found = false
        walk(body, enterLambdas = false, onStmt = { if (it is MStmt.Throw) found = true }, onExpr = {
            if (it is MExpr.Call && (it.fn.throwsKnown || it.fn.mayThrow)) found = true
        })
        return found
    }

    // ---- traversal -----------------------------------------------------------------------------

    internal fun walk(body: List<MStmt>, enterLambdas: Boolean, onStmt: (MStmt) -> Unit, onExpr: (MExpr) -> Unit) {
        fun ex(e: MExpr?) {
            if (e == null) return
            onExpr(e)
            if (e is MExpr.Lambda) { if (enterLambdas) walk(e.body, enterLambdas, onStmt, onExpr); return }
            for (c in e.childExprs()) ex(c)
        }
        fun st(list: List<MStmt>) {
            for (s in list) {
                onStmt(s)
                when (s) {
                    is MStmt.Decl -> ex(s.init)
                    is MStmt.Assign -> ex(s.value)
                    is MStmt.SetField -> { ex(s.obj); ex(s.value) }
                    is MStmt.SetGlobal -> ex(s.value)
                    is MStmt.Eval -> ex(s.expr)
                    is MStmt.If -> { ex(s.cond); st(s.thenBody); st(s.elseBody) }
                    is MStmt.Block -> st(s.body)
                    is MStmt.While -> { st(s.condStmts); ex(s.cond); st(s.body) }
                    is MStmt.DoWhile -> { st(s.body); st(s.condStmts); ex(s.cond) }
                    is MStmt.Return -> ex(s.value)
                    is MStmt.Throw -> ex(s.value)
                    is MStmt.Try -> { st(s.body); s.catches.forEach { st(it.body) }; s.finallyBody?.let { st(it) } }
                    is MStmt.Scope -> st(s.body)
                    is MStmt.Break, is MStmt.Continue -> {}
                }
            }
        }
        st(body)
    }

    // ---- Managed code: boundary only (6.2) ------------------------------------------------------

    private fun checkManaged(f: MFunction, sink: (Diag) -> Unit) {
        fun boundary(e: MExpr) {
            val fn: MFun?
            val args: List<MExpr>
            when (e) {
                is MExpr.Call -> { fn = e.fn; args = e.args }
                is MExpr.New -> { fn = e.ctor; args = e.args }
                else -> return
            }
            if (fn.mode != MemMode.Strict || fn.external) return
            // the kotlinx.cinterop API (C interop) is checked like Strict code but is no boundary: Managed code may call it
            if (fn.qname.startsWith("kotlinx.cinterop.")) return
            for ((i, a) in args.withIndex()) {
                val p = fn.params.getOrNull(i) ?: continue
                if (p.isContext || !p.owned || !a.type.tracked) continue
                sink(
                    Diag(
                        "M0110", Severity.Error, "Managed code passes a value to the `@Owned` parameter of Strict function `${fn.qname.substringAfterLast('.')}`",
                        a.pos, "Managed code cannot fulfil the ownership obligation", emptyList(),
                        listOf("wrap the call in a Strict function that releases the value itself"),
                    ),
                )
            }
            if (e is MExpr.Call && fn.ret.owned) {
                sink(
                    Diag(
                        "M0110", Severity.Error, "Managed code calls Strict function `${fn.qname.substringAfterLast('.')}` which returns an owned value",
                        e.pos, "the result must be released by Strict code", emptyList(),
                        listOf("wrap the call in a Strict function that releases the value itself"),
                    ),
                )
            }
        }
        walk(f.body, enterLambdas = true, onStmt = {}, onExpr = ::boundary)
    }

    // ---- Strict functions ----------------------------------------------------------------------

    private fun checkFunction(f: MFunction, sink: (Diag) -> Unit) {
        val fn = f.fn
        val params = ArrayList<MParam>()
        fn.receiver?.let { params += it }
        params += fn.params
        val ctx0 = Ctx(listOf(fn.params.filter { it.isContext }.map { it.local }))
        // annotation checks on the signature
        val ann = Interp(
            UnitInfo(fn.qname, false, false, false, emptySet(), emptyMap(), false),
            sink, { _, _ -> },
        )
        ann.reporting = true
        for (p in params) ann.annotCheck(p.annot, p.local.type)
        ann.annotCheck(fn.ret.annot, fn.ret.type)
        val idx = HashMap<Int, Int>()
        for ((i, p) in fn.params.withIndex()) idx[p.local.id] = i
        val info = UnitInfo(fn.qname.substringAfterLast('.'), fn.ret.owned, fn.ret.type.tracked, fn.noAlloc, emptySet(), emptyMap(), false, fn, idx)
        runUnit(0, params, f.body, ctx0, info, sink, emptyList())
    }

    private fun runUnit(
        unitId: Int, params: List<MParam>, body: List<MStmt>, ctx0: Ctx, base: UnitInfo, sink: (Diag) -> Unit,
        captured: List<MLocal>,
    ) {
        val b = CfgBuilder(unitId, params.map { it.local }, ctx0)
        val cfg = b.build(body)
        val info = UnitInfo(base.fnName, base.retOwned, base.retTracked, base.noAlloc, b.declared, b.names, base.isLambda, base.fn, base.paramIndex)
        val pending = ArrayList<Pair<MExpr.Lambda, Ctx>>()
        val interp = Interp(info, sink) { l, c -> if (pending.none { it.first.id == l.id }) pending += l to c }

        // entry state
        interp.st = State.EMPTY
        for (c in captured) if (c.type.tracked) interp.st = interp.st.withSlot(c.id, Slot(B or N, emptySet(), emptySet(), c.pos))
        for (p in params) interp.initParam(p)
        val entryState = interp.st

        val ins = arrayOfNulls<State>(cfg.blocks.size)
        ins[cfg.entry.id] = entryState
        val work = ArrayDeque<Blk>()
        work += cfg.entry
        var guard = 0
        while (work.isNotEmpty() && guard++ < 200000) {
            val blk = work.removeFirst()
            val s0 = ins[blk.id] ?: continue
            interp.st = s0
            interp.reporting = false
            for (op in blk.ops) interp.transfer(op)
            val out = interp.st
            for (e in blk.succ) {
                val ns = refine(out, e) ?: continue
                val old = ins[e.to.id]
                val joined = if (old == null) ns else old.join(ns)
                if (old == null || joined != old) {
                    ins[e.to.id] = joined
                    if (e.to !in work) work += e.to
                }
            }
        }
        // reporting pass
        for (blk in cfg.blocks) {
            val s0 = ins[blk.id] ?: continue
            interp.st = s0
            interp.reporting = true
            for (op in blk.ops) interp.transfer(op)
        }
        // escaping lambdas
        for ((l, c) in pending) {
            val lp = l.params.map { MParam(it) }
            runUnit(
                l.id, lp, l.body, c,
                UnitInfo(info.fnName, false, false, info.noAlloc, emptySet(), emptyMap(), true), sink, l.captures,
            )
        }
    }

    private fun refine(s: State, e: Edge): State? {
        if (e.slot < 0) return s
        val sl = s.slots[e.slot] ?: return s
        if (sl.bits == 0) return s
        return if (e.isNull) {
            if (sl.bits and N == 0) null else s.withSlot(e.slot, Slot(N, sl.refs, sl.sites, sl.vpos))
        } else {
            val nb = sl.bits and N.inv()
            if (nb == 0) null else s.withSlot(e.slot, Slot(nb, sl.refs, sl.sites, sl.vpos))
        }
    }
}
