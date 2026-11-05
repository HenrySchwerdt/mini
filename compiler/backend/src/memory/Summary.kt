package mini.backend.memory

/*
 * Inferred function summaries (docs/memory-model.md, "Strict mode in practice").
 *
 * A summary says which parameters of a function ESCAPE: the body stores the value into a field, an array or a global, returns it,
 * moves or frees it, hands it to a callee parameter that escapes (or to a constructor, which owns its property parameters), or
 * captures it in a closure that may outlive the call. Every other parameter is only BORROWED: the callee reads it and passes it on
 * to borrowing callees, and the caller keeps the obligation.
 *
 * Summaries are computed from bodies, transitively (a fixpoint over the call graph, optimistic for recursion), on demand, and
 * cached on the [MFun]. A virtual call (open, abstract, interface method) takes the union over the known overriders. A function
 * without a body that is available (function values, `external` functions, library stubs without a stored summary) has no
 * summary: its parameters count as borrowed, and its result is Managed (the Managed boundary rule of 6.2). Libraries carry their
 * summaries in the `.mlib` ([SummaryText]).
 */
class FnSummary(
    /** Parameter index (into [MFun.params]) -> why the parameter escapes. */
    val escapes: Map<Int, String>,
    /** Parameters that a closure created in the body captures and the function returns: the result borrows them (it does not keep them alive). */
    val carries: Set<Int> = emptySet(),
) {
    fun escapes(i: Int): Boolean = escapes.containsKey(i)

    companion object {
        val NONE = FnSummary(emptyMap())
    }
}

object Summaries {
    /** Functions whose optimistic assumption was used while computing something else (their dependants are not final yet). */
    private var used = HashSet<MFun>()

    /** The summary of [fn], or null when nothing is known (no body, no stored summary, no annotation). */
    fun of(fn: MFun): FnSummary? {
        fn.summary?.let { return it }
        if (fn.summaryBusy) { used.add(fn); return fn.summaryAssumed }
        val body = fn.body ?: fn.bodyProvider?.invoke()
        val targets = fn.virtualTargets?.invoke() ?: emptyList()
        val declared = declared(fn)
        if (body == null && targets.isEmpty()) {
            val st = fn.storedSummary
            if (st == null) return declared
            return if (declared == null) st else combine(listOf(st, declared))
        }
        fn.summaryBusy = true
        val saved = used
        used = HashSet()
        var cur = FnSummary.NONE
        var rounds = 0
        try {
            while (true) {
                fn.summaryAssumed = cur
                val parts = ArrayList<FnSummary>()
                if (body != null) parts.add(analyze(fn, body)) else fn.storedSummary?.let { parts.add(it) }
                if (declared != null) parts.add(declared)
                for (t in targets) if (t !== fn) of(t)?.let { parts.add(it) }
                val next = combine(parts)
                val same = next.escapes.keys == cur.escapes.keys && next.carries == cur.carries
                cur = next
                if (same || rounds++ > 8) break
            }
        } finally {
            fn.summaryBusy = false
            fn.summaryAssumed = null
        }
        used.remove(fn)
        // a result that rests on the assumption of a function still being computed is not final: not cached
        if (used.isEmpty()) fn.summary = cur
        saved.addAll(used)
        used = saved
        return cur
    }

    /** Escapes the declaration states itself: `@TakesOwnership` parameters of the standard library (stores into arrays and nodes). */
    private fun declared(fn: MFun): FnSummary? {
        val t = fn.takesOwnership
        if (t.isEmpty()) return null
        val m = LinkedHashMap<Int, String>()
        for (i in t) m[i] = "takes ownership of it (`@TakesOwnership`)"
        return FnSummary(m)
    }

    /** The summary of a virtual call: it may reach any of the implementations, so an escape in one is an escape of the call. */
    private fun combine(parts: List<FnSummary>): FnSummary {
        if (parts.isEmpty()) return FnSummary.NONE
        val esc = LinkedHashMap<Int, String>()
        val car = LinkedHashSet<Int>()
        for (p in parts) { for ((i, why) in p.escapes) esc.putIfAbsent(i, why); car += p.carries }
        return FnSummary(esc, car - esc.keys)
    }

    /** The reason parameter [i] of [fn] escapes, or null when it is only borrowed (or nothing is known). */
    fun escapeReason(fn: MFun, i: Int): String? = of(fn)?.escapes?.get(i)

    private fun analyze(fn: MFun, body: List<MStmt>): FnSummary = Analysis(fn).run(body)

    /** One run of the escape analysis over the body of [fn]. */
    private class Analysis(val fn: MFun) {
        /** Flow-insensitive alias sets: local id -> parameter indices whose value it may hold. */
        val alias = HashMap<Int, MutableSet<Int>>()
        val esc = LinkedHashMap<Int, String>()
        /** Local id -> parameter indices captured by the closure(s) the local may hold. */
        val carried = HashMap<Int, MutableSet<Int>>()
        val carry = LinkedHashSet<Int>()

        /** Parameters captured by the closure [e] evaluates to (a lambda, an instance of a local class, a call that returns a closure over its arguments). */
        fun carriedOf(e: MExpr?): Set<Int> = when (e) {
            is MExpr.Local -> carried[e.local.id] ?: emptySet()
            is MExpr.Move -> carriedOf(e.target)
            is MExpr.Lambda -> capturedParams(e.captures)
            is MExpr.New -> capturedParams(e.captures)
            is MExpr.Call -> {
                val sum = of(e.fn)
                val out = HashSet<Int>()
                if (sum != null) for (i in sum.carries) { val a = e.args.getOrNull(i); out += paramsOf(a); out += carriedOf(a) }
                out
            }
            else -> emptySet()
        }

        fun capturedParams(caps: List<MLocal>): Set<Int> {
            val out = HashSet<Int>()
            for (c in caps) { alias[c.id]?.let { out += it }; carried[c.id]?.let { out += it } }
            return out
        }

        fun paramsOf(e: MExpr?): Set<Int> = when (e) {
            is MExpr.Local -> alias[e.local.id] ?: emptySet()
            is MExpr.Move -> paramsOf(e.target)
            else -> emptySet()
        }

        /** Escape of the parameters [e] IS (not those a closure [e] only captures). */
        fun escape0(e: MExpr?, why: String) {
            for (i in paramsOf(e)) esc.putIfAbsent(i, why)
        }

        fun escape(e: MExpr?, why: String) {
            for (i in paramsOf(e)) esc.putIfAbsent(i, why)
            for (i in carriedOf(e)) esc.putIfAbsent(i, why)
        }

        fun short(f: MFun) = f.qname.substringAfterLast('.')

        fun run(body: List<MStmt>): FnSummary {
            for ((i, p) in fn.params.withIndex()) if (!p.isContext) alias.getOrPut(p.local.id) { HashSet() }.add(i)
            // property parameters of a constructor are stored into the fields of the new object
            val co = fn.ctorOf
            if (co != null) for ((i, p) in fn.params.withIndex()) if (p.owned) esc[i] = "is stored into a field of `${co.name.substringAfterLast('.')}`"
            MemoryChecker.walk(body, enterLambdas = false, onStmt = { s -> aliases(s) }, onExpr = {})
            MemoryChecker.walk(body, enterLambdas = false, onStmt = { s -> stmt(s) }, onExpr = { e -> expr(e) })
            return FnSummary(esc, carry - esc.keys)
        }

        fun aliases(s: MStmt) {
            when (s) {
                is MStmt.Decl -> { val ps = paramsOf(s.init); if (ps.isNotEmpty()) alias.getOrPut(s.local.id) { HashSet() }.addAll(ps); carry(s.local, s.init) }
                is MStmt.Assign -> { val ps = paramsOf(s.value); if (ps.isNotEmpty()) alias.getOrPut(s.local.id) { HashSet() }.addAll(ps); carry(s.local, s.value) }
                else -> {}
            }
        }

        fun carry(l: MLocal, v: MExpr?) {
            val cs = carriedOf(v)
            if (cs.isNotEmpty()) carried.getOrPut(l.id) { HashSet() }.addAll(cs)
        }

        fun stmt(s: MStmt) {
            when (s) {
                is MStmt.SetField -> escape(s.value, "stored into field `${s.field.name}` of `${s.field.owner.name.substringAfterLast('.')}`")
                is MStmt.SetGlobal -> escape(s.value, "stored into a global")
                // a generic or `@Borrowed` result is a loan of the arguments, not a transfer
                is MStmt.Return -> if (fn.ret.annot.borrowed == null) {
                    escape0(s.value, "returned")
                    // a returned closure over parameters lends them to the result; an instance of a local class cannot (the result is an owner)
                    val cs = carriedOf(s.value)
                    if (s.value !is MExpr.New && !fn.ret.type.tracked) carry += cs else for (i in cs) esc.putIfAbsent(i, "is captured by an object that is returned")
                }
                is MStmt.Throw -> escape(s.value, "thrown")
                is MStmt.Scope -> if (s.kind == ScopeKind.Thread) for (c in s.captures) { alias[c.id]?.forEach { esc.putIfAbsent(it, "captured by a thread") }; carried[c.id]?.forEach { esc.putIfAbsent(it, "captured by a thread") } }
                else -> {}
            }
        }

        fun expr(e: MExpr) {
            when (e) {
                is MExpr.Call -> {
                    val callee = e.fn
                    val sum = of(callee)
                    for ((i, a) in e.args.withIndex()) {
                        val p = callee.params.getOrNull(i) ?: continue
                        if (p.isContext) continue
                        val reason = when {
                            p.annot.borrowed != null -> null
                            p.owned -> "is consumed by `${short(callee)}` (`@Owned` parameter `${p.local.name}`)"
                            sum != null && sum.escapes(i) -> "escapes into `${short(callee)}`, which ${sum.escapes[i]}"
                            else -> null
                        }
                        if (reason != null) escape(a, reason)
                    }
                }
                is MExpr.New -> for ((i, a) in e.args.withIndex()) {
                    val p = e.ctor.params.getOrNull(i) ?: continue
                    if (p.isContext) continue
                    if (p.owned) escape(a, "is stored into `${e.cls?.name?.substringAfterLast('.') ?: "an object"}` (constructor parameter `${p.local.name}`)")
                }
                is MExpr.Free -> escape(e.target, "is released")
                is MExpr.Move -> escape(e.target, "is moved")
                else -> {}
            }
        }
    }
}

/** Text form of summaries inside a `.mlib`: one line per function with an escaping parameter. */
object SummaryText {
    /** `key TAB i:reason TAB i:reason ...`; null when nothing escapes. */
    fun line(key: String, s: FnSummary): String? {
        if (s.escapes.isEmpty() && s.carries.isEmpty()) return null
        val sb = StringBuilder()
        sb.append(key)
        for ((i, why) in s.escapes) sb.append('\t').append(i).append(':').append(why.replace('\t', ' ').replace('\n', ' '))
        for (i in s.carries) sb.append('\t').append('~').append(i)
        return sb.toString()
    }

    fun parse(text: String): Map<String, FnSummary> {
        val out = HashMap<String, FnSummary>()
        for (ln in text.split('\n')) {
            if (ln.isEmpty()) continue
            val parts = ln.split('\t')
            val m = LinkedHashMap<Int, String>()
            val car = LinkedHashSet<Int>()
            for (k in 1 until parts.size) {
                if (parts[k].startsWith("~")) { parts[k].substring(1).toIntOrNull()?.let { car += it }; continue }
                val c = parts[k].indexOf(':')
                if (c <= 0) continue
                val i = parts[k].substring(0, c).toIntOrNull() ?: continue
                m[i] = parts[k].substring(c + 1)
            }
            out[parts[0]] = FnSummary(m, car)
        }
        return out
    }
}
