package mini.backend

/*
 * `minic mem-report` and the M0207 warnings (docs/plan-kotlin.md 4.4a, docs/memory-model.md): where does reference
 * counting remain after Strict code generation, RcOpt and the calling conventions, and why?
 *
 * The report combines two views of the same module: a scan of the MIR classifies every allocation site (arena, heap,
 * pool, scope-local, reference counted) and the emitter records, per function, how many retain / release sites it put into
 * the C by reason (CEmitter.Result.reasons). The counts are static: sites of the emitted C, not executions; the cold
 * exception exit paths are not counted.
 */
object MemReport {
    class FnReport(
        val name: String,
        val cName: String,
        val strict: Boolean,
        /** Allocation sites by kind, e.g. `arena`, `heap`, `scope`, `rc: Managed class`. */
        val allocs: Map<String, Int>,
        val retains: Map<String, Int>,
        val releases: Map<String, Int>,
    ) {
        val retainSites: Int get() = retains.values.sum()
        val releaseSites: Int get() = releases.values.sum()
        val rcSites: Int get() = retainSites + releaseSites
        val rcAllocs: Int get() = allocs.filter { it.key.startsWith("rc") }.values.sum()
    }

    class Report(
        val source: String,
        val functions: List<FnReport>,
        val conventions: List<String>,
        val unknownEffects: List<String>,
    )

    fun build(module: MirModule, r: CEmitter.Result): Report {
        val tr = r.tracking
        val fns = ArrayList<FnReport>()
        for (f in module.functions) {
            val allocs = HashMap<String, Int>()
            scan(f, tr, allocs)
            val rr = r.reasons[f.cName]
            val ret: Map<String, Int> = rr?.first ?: emptyMap()
            val rel: Map<String, Int> = rr?.second ?: emptyMap()
            if (allocs.isEmpty() && ret.isEmpty() && rel.isEmpty()) continue
            fns += FnReport(f.qualifiedName, f.cName, f.strict, allocs, ret, rel)
        }
        for ((name, rr) in r.reasons) {
            if (!name.startsWith("<dispose ")) continue
            fns += FnReport(name, name, false, emptyMap(), rr.first, rr.second)
        }
        return Report(module.sourceName, fns, r.plan?.conv?.summary() ?: emptyList(), r.plan?.unknownEffectFunctions()?.sorted() ?: emptyList())
    }

    private fun bump(m: HashMap<String, Int>, k: String) {
        m[k] = (m[k] ?: 0) + 1
    }

    private fun allocatorKind(e: MirExpr?): String = when {
        e is MirExpr.FieldGet && e.owner.endsWith("Arena") -> "arena"
        e is MirExpr.FieldGet && e.owner.endsWith("Heap") -> "heap"
        e is MirExpr.FieldGet && e.owner.endsWith("Pool") -> "pool"
        e is MirExpr.FieldGet && e.owner.endsWith("DebugAllocator") -> "debug allocator"
        e is MirExpr.RuntimeCall && e.cName == "mk_heap_allocator_h" -> "heap"
        else -> "allocator"
    }

    private fun scanExpr(f: MirFunction, tr: Tracking, e: MirExpr, out: HashMap<String, Int>) {
        when (e) {
            is MirExpr.New -> {
                val c = tr.classOf((e.type as? TObject)?.cName ?: "")
                val strictClass = c?.strict == true && tr.enabled
                if (e.allocator != null && f.strict && strictClass) bump(out, allocatorKind(e.allocator))
                else if (e.auto && f.strict) bump(out, "scope (stack-like, freed at the end of its scope)")
                else if (!strictClass) bump(out, if (c?.cName?.contains("lambda") == true) "rc: closure object" else "rc: instance of a Managed class")
                else if (!f.strict) bump(out, "rc: Strict class constructed in Managed code")
                else bump(out, "rc: Rc allocator in context")
            }
            is MirExpr.Box -> bump(out, "rc: boxed primitive")
            is MirExpr.RuntimeCall ->
                if (e.isOwned()) bump(out, if (e.cName.startsWith("mk_string_") || e.cName == "mk_sb_to_string") "rc: string temporary" else "rc: runtime object (" + e.cName + ")")
            else -> {}
        }
        for (c in mirChildren(e)) scanExpr(f, tr, c, out)
    }

    private fun scanStmts(f: MirFunction, tr: Tracking, list: List<MirStmt>, out: HashMap<String, Int>) {
        for (s in list) {
            when (s) {
                is MirStmt.Declare -> s.init?.let { scanExpr(f, tr, it, out) }
                is MirStmt.Assign -> scanExpr(f, tr, s.value, out)
                is MirStmt.SetField -> { scanExpr(f, tr, s.obj, out); scanExpr(f, tr, s.value, out) }
                is MirStmt.Eval -> scanExpr(f, tr, s.expr, out)
                is MirStmt.If -> { scanExpr(f, tr, s.cond, out); scanStmts(f, tr, s.thenBody, out); scanStmts(f, tr, s.elseBody, out) }
                is MirStmt.Block -> scanStmts(f, tr, s.body, out)
                is MirStmt.While -> { scanExpr(f, tr, s.cond, out); scanStmts(f, tr, s.body, out) }
                is MirStmt.Loop -> scanStmts(f, tr, s.body, out)
                is MirStmt.DoWhile -> { scanStmts(f, tr, s.body, out); scanStmts(f, tr, s.condStmts, out); scanExpr(f, tr, s.cond, out) }
                is MirStmt.ForRange -> { scanExpr(f, tr, s.start, out); scanExpr(f, tr, s.end, out); s.step?.let { scanExpr(f, tr, it, out) }; scanStmts(f, tr, s.body, out) }
                is MirStmt.Return -> s.value?.let { scanExpr(f, tr, it, out) }
                is MirStmt.Throw -> scanExpr(f, tr, s.value, out)
                is MirStmt.Try -> { scanStmts(f, tr, s.body, out); for (c in s.catches) scanStmts(f, tr, c.body, out) }
                is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> {}
            }
        }
    }

    private fun scan(f: MirFunction, tr: Tracking, out: HashMap<String, Int>) = scanStmts(f, tr, f.body, out)

    private fun sum(maps: List<Map<String, Int>>): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (m in maps) for ((k, v) in m) out[k] = (out[k] ?: 0) + v
        return out
    }

    private fun histogram(m: Map<String, Int>): String =
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).joinToString("; ") { "${it.key} ${it.value}" }

    /** The text of `minic mem-report`. [filter] selects the functions listed in detail (the totals cover all). */
    fun text(r: Report, top: Int = 40, filter: (FnReport) -> Boolean = { true }): String {
        val sb = StringBuilder()
        val all = r.functions
        val allocs = sum(all.map { it.allocs })
        val retains = sum(all.map { it.retains })
        val releases = sum(all.map { it.releases })
        sb.append("mem-report: ${r.source}: ${all.size} functions with allocations or reference counting, ${all.count { it.strict }} Strict\n")
        sb.append("allocation sites: ${allocs.values.sum()} (rc ${allocs.filter { it.key.startsWith("rc") }.values.sum()})\n")
        if (allocs.isNotEmpty()) sb.append("  ").append(histogram(allocs)).append('\n')
        sb.append("reference counting left in the C: ${retains.values.sum()} retain sites, ${releases.values.sum()} release sites\n")
        if (retains.isNotEmpty()) sb.append("  retains by reason: ").append(histogram(retains)).append('\n')
        if (releases.isNotEmpty()) sb.append("  releases by reason: ").append(histogram(releases)).append('\n')
        if (r.conventions.isNotEmpty()) {
            sb.append("calling conventions:\n")
            for (c in r.conventions) sb.append("  ").append(c.trimStart()).append('\n')
        }
        if (r.unknownEffects.isNotEmpty()) {
            sb.append("functions with unknown heap effects (every borrowed alias dies at a call of them): ${r.unknownEffects.size}\n")
            for (n in r.unknownEffects.take(10)) sb.append("  ").append(n).append('\n')
            if (r.unknownEffects.size > 10) sb.append("  ... ${r.unknownEffects.size - 10} more\n")
        }
        val listed = all.filter(filter).filter { it.rcSites > 0 || it.rcAllocs > 0 }
            .sortedWith(compareByDescending<FnReport> { it.rcSites + it.rcAllocs }.thenBy { it.name })
        sb.append("functions with reference counting (${listed.size}, top ${minOf(top, listed.size)} by sites):\n")
        for (f in listed.take(top)) {
            sb.append("  ").append(f.name).append(if (f.strict) "  [Strict]" else "").append('\n')
            if (f.allocs.isNotEmpty()) sb.append("      allocations: ").append(histogram(f.allocs)).append('\n')
            if (f.retains.isNotEmpty()) sb.append("      retains: ").append(histogram(f.retains)).append('\n')
            if (f.releases.isNotEmpty()) sb.append("      releases: ").append(histogram(f.releases)).append('\n')
        }
        return sb.toString()
    }

    private fun suggestion(reason: String): String =
        if (reason.startsWith("reference stored into a field")) {
            "a field owns one count; in Strict code make the field `@Owned` (the object is freed with its owner) or `@Borrowed` (a back reference)"
        } else if (reason.startsWith("borrowed value copied")) {
            "a value read from a parameter or field is kept in a local across a call that may overwrite heap references; use it before the call, or make the callee `@Borrowed`"
        } else if (reason.startsWith("owned local released")) {
            "the local holds an owned result (+1); return it as `@Owned`, or allocate it in an `Arena` around the block"
        } else if (reason.startsWith("owned parameter released")) {
            "the parameter is owned and not consumed on every path; consume it or take it as borrowed"
        } else if (reason.startsWith("reference handed to an owned parameter")) {
            "the caller still needs its reference; move it with `x.move()` at its last use"
        } else if (reason.startsWith("returned reference")) {
            "the function returns a reference it does not own; return `@Borrowed`, or an owned fresh value"
        } else if (reason.startsWith("old value") || reason.startsWith("unused owned result") || reason.startsWith("field of a dying object")) {
            "the value dies here; in Strict code `x.free()` / the free glue replaces the count"
        } else {
            "make the class Strict and allocate it in an `Arena` / `Heap`, or keep the reference counting"
        }

    /**
     * M0207 (warning): reference counting remains in a function. One warning per function with the dominant reason
     * and a suggestion. [accept] selects the functions (user code); the MIR has no source positions, so the
     * location is the function's qualified name.
     */
    fun m0207(r: Report, accept: (FnReport) -> Boolean): List<String> {
        val out = ArrayList<String>()
        for (f in r.functions.filter(accept).filter { it.rcSites > 0 || it.rcAllocs > 0 }.sortedBy { it.name }) {
            val sb = StringBuilder()
            sb.append("warning[M0207]: reference counting remains in `${f.name}` (${f.retainSites} retain, ${f.releaseSites} release sites, ${f.rcAllocs} counted allocations)\n")
            val both = HashMap<String, Int>()
            for ((k, v) in f.retains) both[k] = (both[k] ?: 0) + v
            for ((k, v) in f.releases) both[k] = (both[k] ?: 0) + v
            val dominant = both.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
            if (dominant != null) {
                sb.append("  note: mostly ${dominant.key} (${dominant.value} sites)\n")
                sb.append("  help: ${suggestion(dominant.key)}\n")
            } else {
                val a = f.allocs.entries.firstOrNull { it.key.startsWith("rc") }
                if (a != null) sb.append("  note: ${a.key} (${a.value} allocations)\n  help: ${suggestion(a.key)}\n")
            }
            out += sb.toString()
        }
        return out
    }
}
