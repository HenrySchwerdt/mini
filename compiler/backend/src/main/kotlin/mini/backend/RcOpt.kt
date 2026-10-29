package mini.backend

/*
 * RcOpt: reference-count elision for the Managed (reference counting) code generation.
 * Rules and the safety argument: compiler/README.md, section "RC elision (RcOpt)".
 *
 * RcOpt does not rewrite the MIR. It analyses every function and produces an [RcPlan] that
 * [CEmitter] consults when it places retain/release (the emitter without a plan is the baseline
 * code generation, `minic build --no-rc-opt`). The plan contains, per function,
 *
 *  - the reference locals that BORROW (they alias a reference that stays alive for their whole
 *    lifetime: no retain at the definition, no release at scope exits), and
 *  - the MOVES: a statement that consumes the last use of an owned local takes over the local's
 *    reference instead of retaining a second one (the emitter then clears the local).
 *
 * The pass is a pure function of the MIR module, deterministic, and (like the rest of the backend)
 * pure Kotlin: it must give byte-identical results on the JVM and natively (SelfHostTest).
 *
 * Safety argument in one paragraph. A borrowed local `x` is only used while the object it points to
 * is kept alive by something else, which is one of: (a) a parameter (borrowed from the caller for the
 * whole call), (b) an owned local that is not reassigned or moved during the lifetime of `x`, (c) an
 * immortal singleton, or (d) a field of such an object that is read through a chain of those, and
 * then NO operation that can overwrite a reference stored in the heap (a reference field store, a
 * reference array store, a call that may do one, see [RcEffects]) may run between the read and the
 * last use of `x`. A call that uses the alias as an argument counts as running at its end.
 */

/** Decisions for one function (see the file comment). Keys: local ids / consumer statements. */
class RcFunctionPlan(
    /** Ids of the reference locals that borrow instead of own. */
    val borrowed: HashSet<Int>,
    /** Consumer statement (Declare / Assign / SetField / Return) to the owned local whose reference it takes over. */
    val moves: HashMap<MirStmt, MirLocal>,
) {
    fun isBorrowed(l: MirLocal): Boolean = l.id in borrowed
}

/** Counters of what the pass decided (tests, `docs/benchmarks.md`). */
class RcStats {
    var functions = 0
    var refLocals = 0
    var borrowedLocals = 0
    var moves = 0
}

class RcPlan internal constructor(private val byFunction: HashMap<String, RcFunctionPlan>, val stats: RcStats) {
    fun of(f: MirFunction): RcFunctionPlan? = byFunction[f.cName]
}

object RcOpt {
    fun analyze(module: MirModule): RcPlan {
        val fx = RcEffects(module).run()
        val plans = HashMap<String, RcFunctionPlan>()
        val stats = RcStats()
        for (f in module.functions) {
            stats.functions++
            val plan = RcAnalyzer(f, fx, stats).run()
            if (plan != null) plans[f.cName] = plan
        }
        return RcPlan(plans, stats)
    }
}

/** Operands of [e] in evaluation order. */
internal fun mirChildren(e: MirExpr): List<MirExpr> = when (e) {
    is MirExpr.Binary -> listOf(e.left, e.right)
    is MirExpr.Unary -> listOf(e.operand)
    is MirExpr.Convert -> listOf(e.operand)
    is MirExpr.Call -> e.args
    is MirExpr.RuntimeCall -> e.args
    is MirExpr.Conditional -> listOf(e.cond, e.thenValue, e.elseValue)
    is MirExpr.Box -> listOf(e.operand)
    is MirExpr.Unbox -> listOf(e.operand)
    is MirExpr.Cast -> listOf(e.operand)
    is MirExpr.IsInstance -> listOf(e.operand)
    is MirExpr.NotNull -> listOf(e.operand)
    is MirExpr.FieldGet -> listOf(e.obj)
    is MirExpr.VCall -> listOf(e.receiver) + e.args
    is MirExpr.ICall -> listOf(e.receiver) + e.args
    is MirExpr.New -> e.args
    is MirExpr.StringLength -> listOf(e.operand)
    is MirExpr.IntConst, is MirExpr.LongConst, is MirExpr.DoubleConst, is MirExpr.BooleanConst, is MirExpr.CharConst,
    is MirExpr.StringConst, MirExpr.UnitValue, is MirExpr.Local, is MirExpr.NullConst, is MirExpr.ObjectGet,
    is MirExpr.Unreachable, is MirExpr.CaughtException, is MirExpr.TypeInfoOf -> emptyList()
}

/**
 * Which functions may overwrite a reference that lives in the heap (a reference field or array slot)?
 * Such an operation can free an object a borrowed alias still points to, so it ends the lifetime of
 * every field-derived alias. Computed as a fixpoint like [ThrowAnalysis].
 *
 * A reference field store into the object a constructor is initialising (`this`) does not count for
 * the summary of that constructor: the object is invisible to everybody else, and releasing one of
 * its own fields can never remove the last count of an object a caller's alias depends on.
 */
internal class RcEffects(private val module: MirModule) {
    val mutating = HashSet<String>()
    private val slotImpls = HashMap<Int, HashSet<String>>()
    private val ifaceImpls = HashMap<String, HashSet<String>>()

    fun run(): RcEffects {
        for (c in module.classes) {
            for ((slot, name) in c.vtable.withIndex()) {
                if (name == null) continue
                slotImpls.getOrPut(slot) { HashSet() }.add(name)
            }
            for (t in c.itables) {
                for ((index, name) in t.entries.withIndex()) {
                    if (name == null) continue
                    ifaceImpls.getOrPut(ifaceKey(t.interfaceCName, index)) { HashSet() }.add(name)
                }
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for (f in module.functions) {
                if (f.cName in mutating) continue
                if (f.body.any { stmtMutates(f, it) }) {
                    mutating += f.cName
                    changed = true
                }
            }
        }
        return this
    }

    private fun ifaceKey(iface: String, index: Int) = "$iface#$index"

    private fun slotMutates(slot: Int): Boolean = slotImpls[slot]?.any { it in mutating } ?: false

    private fun anyDynamicMutates(): Boolean =
        slotImpls.values.any { s -> s.any { it in mutating } } || ifaceImpls.values.any { s -> s.any { it in mutating } }

    /** True when evaluating the call-like expression [e] itself (not its operands) may overwrite a heap reference. */
    fun callMutates(e: MirExpr): Boolean = when (e) {
        is MirExpr.Call -> e.cName in mutating
        is MirExpr.New -> e.ctorCName in mutating
        is MirExpr.VCall -> slotMutates(e.slot)
        is MirExpr.ICall -> ifaceImpls[ifaceKey(e.interfaceCName, e.index)]?.any { it in mutating } ?: false
        is MirExpr.RuntimeCall -> runtimeMutates(e.cName)
        else -> false
    }

    private fun runtimeMutates(name: String): Boolean {
        if (name == "mk_to_string" || name == "mk_equals" || name == "mk_hash_code") {
            // dispatch through slots 0..2 of the class vtables (user overrides, data classes)
            return slotMutates(0) || slotMutates(1) || slotMutates(2)
        }
        if (name == "mk_compare_to" || name == "mk_array_content_to_string") return anyDynamicMutates()
        return !isPureRuntime(name)
    }

    private fun stmtMutates(f: MirFunction, s: MirStmt): Boolean = when (s) {
        is MirStmt.Declare -> s.init?.let { exprMutates(it) } ?: false
        is MirStmt.Assign -> exprMutates(s.value)
        is MirStmt.SetField -> exprMutates(s.obj) || exprMutates(s.value) || (s.fieldType.isRef && !isFreshThisStore(f, s))
        is MirStmt.Eval -> exprMutates(s.expr)
        is MirStmt.If -> exprMutates(s.cond) || s.thenBody.any { stmtMutates(f, it) } || s.elseBody.any { stmtMutates(f, it) }
        is MirStmt.Block -> s.body.any { stmtMutates(f, it) }
        is MirStmt.While -> exprMutates(s.cond) || s.body.any { stmtMutates(f, it) }
        is MirStmt.Loop -> s.body.any { stmtMutates(f, it) }
        is MirStmt.DoWhile -> s.body.any { stmtMutates(f, it) } || s.condStmts.any { stmtMutates(f, it) } || exprMutates(s.cond)
        is MirStmt.ForRange ->
            exprMutates(s.start) || exprMutates(s.end) || (s.step?.let { exprMutates(it) } ?: false) || s.body.any { stmtMutates(f, it) }
        is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> false
        is MirStmt.Return -> s.value?.let { exprMutates(it) } ?: false
        is MirStmt.Throw -> exprMutates(s.value)
        is MirStmt.Try -> s.body.any { stmtMutates(f, it) } || s.catches.any { c -> c.body.any { stmtMutates(f, it) } }
    }

    private fun exprMutates(e: MirExpr): Boolean = callMutates(e) || mirChildren(e).any { exprMutates(it) }

    private fun isFreshThisStore(f: MirFunction, s: MirStmt.SetField): Boolean {
        if (f.constructorOf == null || f.params.isEmpty()) return false
        val o = s.obj
        return o is MirExpr.Local && o.local === f.params[0]
    }

    companion object {
        /** Runtime functions that never overwrite a reference stored in a field or an array slot of an existing object. */
        fun isPureRuntime(name: String): Boolean =
            name.startsWith("mk_string_") || name.startsWith("mk_print") || name.startsWith("mk_box_") ||
                name.startsWith("mk_checked_") || name == "mk_is_instance" || name == "mk_ref_array_get" ||
                name == "mk_ref_array_length" || name == "mk_ref_array_new" || name.startsWith("mk_array_new") ||
                name == "mk_array_length" || name.startsWith("mk_array_get_") ||
                name == "mk_array_set_int" || name == "mk_array_set_long" || name == "mk_array_set_double" ||
                name == "mk_array_set_boolean" || name == "mk_array_set_char" || name.startsWith("mk_sb_") ||
                name == "mk_type_name" || name == "mk_identity_hash" || name == "mk_any_new" || name.startsWith("mk_any_") ||
                name.startsWith("mk_b1_") || name.startsWith("mk_raise") || name == "mk_throwable_init" ||
                (name.startsWith("Mk") && name.endsWith("_new"))
    }
}

/** A small bit set over dense local indices. */
internal class RcBits(val size: Int) {
    private val words = LongArray(size / 64 + 1)
    fun get(i: Int): Boolean = (words[i / 64] ushr (i % 64)) and 1L != 0L
    fun set(i: Int) {
        words[i / 64] = words[i / 64] or (1L shl (i % 64))
    }
    fun clear(i: Int) {
        words[i / 64] = words[i / 64] and (1L shl (i % 64)).inv()
    }
    fun copy(): RcBits {
        val b = RcBits(size)
        for (k in words.indices) b.words[k] = words[k]
        return b
    }
    fun orWith(o: RcBits) {
        for (k in words.indices) words[k] = words[k] or o.words[k]
    }
    fun sameAs(o: RcBits): Boolean {
        for (k in words.indices) if (words[k] != o.words[k]) return false
        return true
    }
}

private class LoopRange(val start: Int) {
    var end = Int.MAX_VALUE
}

/** The chain of reads a definition of a candidate borrower consists of (`x = a.b.c` has root `a`, one field read). */
private class Chain(val roots: ArrayList<MirLocal>, var hasField: Boolean)

private class LocalInfo(val local: MirLocal, val index: Int, val isParam: Boolean) {
    var firstDef = -1
    var declScope = 0
    var declDepth = 0
    var ownedDef = false
    var duplicate = false
    val chains = ArrayList<Chain>()
    val usePos = ArrayList<Int>()
    val useLoop = ArrayList<LoopRange?>()
    val assignPos = ArrayList<Int>()
    val assignLoop = ArrayList<LoopRange?>()
    var borrowed = false
    // derived by the validation
    val roots = ArrayList<LocalInfo>()
    var viaField = false
}

private class RcAnalyzer(val f: MirFunction, val fx: RcEffects, val stats: RcStats) {
    private val infos = ArrayList<LocalInfo>()
    private val byId = HashMap<Int, LocalInfo>()
    private var pos = 0
    private val events = ArrayList<Int>()
    private val loops = ArrayList<LoopRange>()
    private val scopeParent = ArrayList<Int>()
    private var scope = 0
    private val moves = HashMap<MirStmt, MirLocal>()

    fun run(): RcFunctionPlan? {
        scopeParent += -1
        for (p in f.params) if (p.type.isRef) info(p, true)
        for (s in f.body) stmt(s)
        // loop ends are known now; select and validate the borrowers
        var candidates = 0
        for (i in infos) {
            i.borrowed = !i.isParam && !i.ownedDef && !i.duplicate && i.chains.isNotEmpty()
            if (i.borrowed) candidates++
        }
        if (candidates > 0) validate()
        liveness()
        var borrowedCount = 0
        val borrowedIds = HashSet<Int>()
        for (i in infos) if (i.borrowed) {
            borrowedIds += i.local.id
            borrowedCount++
        }
        stats.refLocals += infos.size
        stats.borrowedLocals += borrowedCount
        stats.moves += moves.size
        if (borrowedIds.isEmpty() && moves.isEmpty()) return null
        return RcFunctionPlan(borrowedIds, moves)
    }

    // ---- collection: positions, uses, definitions, events --------------------------------------------------

    private fun info(l: MirLocal, param: Boolean): LocalInfo {
        val existing = byId[l.id]
        if (existing != null) {
            existing.duplicate = true
            return existing
        }
        val i = LocalInfo(l, infos.size, param)
        i.declScope = scope
        i.declDepth = loops.size
        infos += i
        byId[l.id] = i
        return i
    }

    private fun use(l: MirLocal, p: Int) {
        val i = byId[l.id] ?: return
        i.usePos += p
        i.useLoop += loops.getOrNull(i.declDepth)
    }

    /** `Cast`/`NotNull` wrappers around a local (value-preserving for the pointer): the local, or null. */
    private fun directLocal(e: MirExpr): MirLocal? = when (e) {
        is MirExpr.Local -> e.local
        is MirExpr.Cast -> directLocal(e.operand)
        is MirExpr.NotNull -> directLocal(e.operand)
        else -> null
    }

    private fun chainOf(e: MirExpr, acc: Chain): Boolean = when (e) {
        is MirExpr.Local -> {
            acc.roots += e.local
            true
        }
        is MirExpr.Cast -> chainOf(e.operand, acc)
        is MirExpr.NotNull -> chainOf(e.operand, acc)
        is MirExpr.FieldGet -> {
            acc.hasField = true
            e.type.isRef && chainOf(e.obj, acc)
        }
        is MirExpr.ObjectGet, is MirExpr.StringConst, is MirExpr.NullConst -> true
        else -> false
    }

    private fun addDef(i: LocalInfo, e: MirExpr, p: Int) {
        if (i.firstDef < 0) i.firstDef = p
        val c = Chain(ArrayList(), false)
        if (e.isOwned() || !chainOf(e, c)) i.ownedDef = true else i.chains += c
    }

    private fun expr(e: MirExpr) {
        when (e) {
            is MirExpr.Local -> use(e.local, pos++)
            is MirExpr.Call, is MirExpr.RuntimeCall, is MirExpr.New, is MirExpr.VCall, is MirExpr.ICall -> {
                val ops = mirChildren(e)
                for (o in ops) expr(o)
                callEvent(ops, fx.callMutates(e))
            }
            else -> for (c in mirChildren(e)) expr(c)
        }
    }

    /** The operation itself runs after its operands: direct local operands are in use until then. */
    private fun callEvent(operands: List<MirExpr>, mutates: Boolean) {
        val p = pos++
        if (mutates) events += p
        for (o in operands) {
            val l = directLocal(o)
            if (l != null) use(l, p)
        }
    }

    private fun stmts(list: List<MirStmt>) {
        scopeParent += scope
        val saved = scope
        scope = scopeParent.size - 1
        for (s in list) stmt(s)
        scope = saved
    }

    private fun loopBegin(): LoopRange {
        val l = LoopRange(pos++)
        loops += l
        return l
    }

    private fun loopEnd(l: LoopRange) {
        l.end = pos++
        loops.removeAt(loops.size - 1)
    }

    private fun stmt(s: MirStmt) {
        when (s) {
            is MirStmt.Declare -> {
                val init = s.init
                if (init != null) expr(init)
                if (s.local.type.isRef) {
                    val i = info(s.local, false)
                    if (init != null) addDef(i, init, pos++)
                }
            }
            is MirStmt.Assign -> {
                expr(s.value)
                if (s.local.type.isRef) {
                    val p = pos++
                    val i = byId[s.local.id]
                    if (i == null) return
                    i.assignPos += p
                    i.assignLoop += loops.getOrNull(i.declDepth)
                    if (i.isParam) i.duplicate = true // never expected; keeps params out of the alias roots
                    addDef(i, s.value, p)
                }
            }
            is MirStmt.SetField -> {
                expr(s.obj)
                expr(s.value)
                val p = pos++
                if (s.fieldType.isRef) events += p
                val a = directLocal(s.obj)
                if (a != null) use(a, p)
                val b = directLocal(s.value)
                if (b != null) use(b, p)
            }
            is MirStmt.Eval -> expr(s.expr)
            is MirStmt.If -> {
                expr(s.cond)
                stmts(s.thenBody)
                stmts(s.elseBody)
            }
            is MirStmt.Block -> stmts(s.body)
            is MirStmt.While -> {
                val l = loopBegin()
                expr(s.cond)
                stmts(s.body)
                loopEnd(l)
            }
            is MirStmt.Loop -> {
                val l = loopBegin()
                stmts(s.body)
                loopEnd(l)
            }
            is MirStmt.DoWhile -> {
                val l = loopBegin()
                // body and condition statements share one scope (Kotlin scoping, see CEmitter.doWhile)
                scopeParent += scope
                val saved = scope
                scope = scopeParent.size - 1
                for (b in s.body) stmt(b)
                for (c in s.condStmts) stmt(c)
                expr(s.cond)
                scope = saved
                loopEnd(l)
            }
            is MirStmt.ForRange -> {
                expr(s.start)
                expr(s.end)
                s.step?.let { expr(it) }
                val l = loopBegin()
                stmts(s.body)
                loopEnd(l)
            }
            is MirStmt.Break, is MirStmt.Continue, is MirStmt.Panic -> {}
            is MirStmt.Return -> {
                s.value?.let { expr(it) }
                pos++
            }
            is MirStmt.Throw -> expr(s.value)
            is MirStmt.Try -> {
                stmts(s.body)
                for (c in s.catches) stmts(c.body)
            }
        }
    }

    // ---- validation of the borrowers ----------------------------------------------------------------------

    private fun effective(p: Int, loop: LoopRange?): Int = if (loop != null) loop.end else p

    private fun windowEnd(i: LocalInfo): Int {
        var end = i.firstDef
        for (k in i.usePos.indices) end = maxOf(end, effective(i.usePos[k], i.useLoop[k]))
        for (k in i.assignPos.indices) end = maxOf(end, effective(i.assignPos[k], i.assignLoop[k]))
        return end
    }

    private fun isAncestorOrSame(a: Int, b: Int): Boolean {
        var s = b
        while (s >= 0) {
            if (s == a) return true
            s = scopeParent[s]
        }
        return false
    }

    /** Computes [LocalInfo.roots] / [LocalInfo.viaField] of a borrower through other borrowers; false when a root is unknown. */
    private fun closure(x: LocalInfo): Boolean {
        x.roots.clear()
        x.viaField = false
        val seen = ArrayList<LocalInfo>()
        seen += x
        return walk(x, i = x, seen = seen)
    }

    private fun walk(x: LocalInfo, i: LocalInfo, seen: ArrayList<LocalInfo>): Boolean {
        var ok = true
        for (c in i.chains) {
            if (c.hasField) x.viaField = true
            for (r in c.roots) {
                val ri = byId[r.id]
                if (ri == null) {
                    ok = false
                } else if (ri.borrowed) {
                    if (seen.none { it === ri }) {
                        seen += ri
                        if (!walk(x, ri, seen)) ok = false
                    }
                } else if (x.roots.none { it === ri }) {
                    x.roots += ri
                }
            }
        }
        return ok
    }

    private fun eventIn(lo: Int, hi: Int): Boolean {
        // events are sorted ascending: find the first one > lo
        var a = 0
        var b = events.size
        while (a < b) {
            val m = (a + b) / 2
            if (events[m] > lo) b = m else a = m + 1
        }
        return a < events.size && events[a] <= hi
    }

    private fun valid(x: LocalInfo): Boolean {
        if (!closure(x)) return false
        val lo = x.firstDef
        val hi = windowEnd(x)
        if (x.viaField && eventIn(lo, hi)) return false
        for (r in x.roots) {
            if (r.isParam) {
                if (r.duplicate) return false
                continue
            }
            if (r.duplicate) return false
            if (!isAncestorOrSame(r.declScope, x.declScope)) return false
            for (k in r.assignPos.indices) {
                val a = r.assignPos[k]
                if (a in lo..hi) return false
            }
        }
        return true
    }

    private fun validate() {
        var changed = true
        while (changed) {
            changed = false
            for (i in infos) {
                if (i.borrowed && !valid(i)) {
                    i.borrowed = false
                    changed = true
                }
            }
        }
        for (i in infos) if (i.borrowed) closure(i)
    }

    // ---- liveness and moves --------------------------------------------------------------------------------

    private val breakLive = HashMap<Int, RcBits>()
    private val contLive = HashMap<Int, RcBits>()
    private var excLive: RcBits? = null

    private fun empty() = RcBits(infos.size)

    private fun addUses(e: MirExpr, into: RcBits) {
        if (e is MirExpr.Local) {
            val i = byId[e.local.id] ?: return
            into.set(i.index)
            if (i.borrowed) for (r in i.roots) into.set(r.index)
            return
        }
        for (c in mirChildren(e)) addUses(c, into)
    }

    private fun countUses(e: MirExpr, l: MirLocal): Int {
        if (e is MirExpr.Local) return if (e.local === l) 1 else 0
        var n = 0
        for (c in mirChildren(e)) n += countUses(c, l)
        return n
    }

    /** The local whose reference a value expression passes on unchanged (through casts that cannot lose it). */
    private fun movable(e: MirExpr?): MirLocal? = when (e) {
        is MirExpr.Local -> e.local
        is MirExpr.Cast -> if (e.mode == MirExpr.CastMode.Safe) null else movable(e.operand)
        is MirExpr.NotNull -> movable(e.operand)
        else -> null
    }

    private fun liveness() {
        if (infos.isEmpty()) return
        liveList(f.body, empty(), true)
    }

    private fun liveList(list: List<MirStmt>, out: RcBits, record: Boolean): RcBits {
        var cur = out
        for (k in list.indices.reversed()) cur = liveStmt(list[k], cur, record)
        return cur
    }

    /** Records a move when [s] consumes the last use of an owned local. [out] is the live set after [s]. */
    private fun consume(s: MirStmt, value: MirExpr?, out: RcBits, target: MirLocal?, record: Boolean) {
        if (!record) return
        val l = movable(value) ?: return
        val x = byId[l.id] ?: return
        if (x.isParam || x.borrowed || x.duplicate || out.get(x.index)) return
        if (target != null) {
            val t = byId[target.id] ?: return
            if (t.borrowed || t === x) return
        }
        if (value == null || countUses(value, l) != 1) return
        if (s is MirStmt.SetField && (countUses(s.obj, l) != 0)) return
        moves[s] = l
    }

    private fun liveStmt(s: MirStmt, out0: RcBits, record: Boolean): RcBits {
        val exc = excLive
        val out = if (exc == null) out0 else out0.copy().also { it.orWith(exc) }
        when (s) {
            is MirStmt.Declare -> {
                val init = s.init
                consume(s, init, out, s.local, record)
                val inn = out.copy()
                byId[s.local.id]?.let { inn.clear(it.index) }
                if (init != null) addUses(init, inn)
                return inn
            }
            is MirStmt.Assign -> {
                consume(s, s.value, out, s.local, record)
                val inn = out.copy()
                byId[s.local.id]?.let { inn.clear(it.index) }
                addUses(s.value, inn)
                return inn
            }
            is MirStmt.SetField -> {
                if (s.fieldType.isRef) consume(s, s.value, out, null, record)
                val inn = out.copy()
                addUses(s.obj, inn)
                addUses(s.value, inn)
                return inn
            }
            is MirStmt.Eval -> {
                val inn = out.copy()
                addUses(s.expr, inn)
                return inn
            }
            is MirStmt.If -> {
                val t = liveList(s.thenBody, out, record)
                val e = liveList(s.elseBody, out, record)
                val inn = t.copy()
                inn.orWith(e)
                addUses(s.cond, inn)
                return inn
            }
            is MirStmt.Block -> return liveList(s.body, out, record)
            is MirStmt.While -> {
                breakLive[s.label.id] = out
                val condUses = empty()
                addUses(s.cond, condUses)
                var head = out.copy()
                head.orWith(condUses)
                while (true) {
                    contLive[s.label.id] = head
                    val bodyIn = liveList(s.body, head, false)
                    val next = head.copy()
                    next.orWith(bodyIn)
                    if (next.sameAs(head)) break
                    head = next
                }
                contLive[s.label.id] = head
                if (record) liveList(s.body, head, true)
                return head
            }
            is MirStmt.Loop -> {
                breakLive[s.label.id] = out
                var head = empty()
                while (true) {
                    contLive[s.label.id] = head
                    val bodyIn = liveList(s.body, head, false)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                contLive[s.label.id] = head
                if (record) liveList(s.body, head, true)
                return head
            }
            is MirStmt.DoWhile -> {
                breakLive[s.label.id] = out
                val condUses = empty()
                addUses(s.cond, condUses)
                var head = empty()
                var condStart = empty()
                while (true) {
                    val afterCond = condUses.copy()
                    afterCond.orWith(head)
                    afterCond.orWith(out)
                    condStart = liveList(s.condStmts, afterCond, false)
                    contLive[s.label.id] = condStart
                    val bodyIn = liveList(s.body, condStart, false)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                if (record) {
                    val afterCond = condUses.copy()
                    afterCond.orWith(head)
                    afterCond.orWith(out)
                    condStart = liveList(s.condStmts, afterCond, true)
                    contLive[s.label.id] = condStart
                    liveList(s.body, condStart, true)
                }
                return head
            }
            is MirStmt.ForRange -> {
                breakLive[s.label.id] = out
                var head = empty()
                while (true) {
                    val after = head.copy()
                    after.orWith(out)
                    contLive[s.label.id] = after
                    val bodyIn = liveList(s.body, after, false)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                val after = head.copy()
                after.orWith(out)
                contLive[s.label.id] = after
                if (record) liveList(s.body, after, true)
                val inn = after.copy()
                addUses(s.start, inn)
                addUses(s.end, inn)
                s.step?.let { addUses(it, inn) }
                return inn
            }
            is MirStmt.Break -> return breakLive[s.label.id] ?: empty()
            is MirStmt.Continue -> return contLive[s.label.id] ?: empty()
            is MirStmt.Panic -> return empty()
            is MirStmt.Return -> {
                consume(s, s.value, empty(), null, record && f.returnType.isRef)
                val inn = empty()
                s.value?.let { addUses(it, inn) }
                return inn
            }
            is MirStmt.Throw -> {
                val inn = empty()
                addUses(s.value, inn)
                excLive?.let { inn.orWith(it) }
                return inn
            }
            is MirStmt.Try -> {
                val outer = excLive
                val handlers = empty()
                for (c in s.catches) handlers.orWith(liveList(c.body, out0, record))
                val inBody = handlers.copy()
                if (outer != null) inBody.orWith(outer)
                excLive = inBody
                val bodyIn = liveList(s.body, out0, record)
                excLive = outer
                val inn = bodyIn.copy()
                inn.orWith(handlers)
                return inn
            }
        }
    }
}
