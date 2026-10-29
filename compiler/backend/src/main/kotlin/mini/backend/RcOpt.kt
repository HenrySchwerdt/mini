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
    /** Call argument expression to the owned local whose reference the callee takes over (see [RcPlan.ownedMask]). */
    val takes: HashMap<MirExpr, MirLocal>,
    /** Parameters this function owns (owned convention): it releases them at its exits unless it moved them. */
    val ownedParams: List<MirLocal>,
    /**
     * After this statement the listed locals have given their reference away on every path to every
     * later exit of their scope: the emitter neither clears nor releases them there.
     */
    val dead: HashMap<MirStmt, ArrayList<MirLocal>>,
    /** Reference field stores into the object a constructor initialises where the old value is known to be NULL. */
    val fresh: HashSet<MirStmt>,
    /** Assignments whose value expression hands the assigned local's own old reference to a callee: the old value is gone. */
    val selfTakes: HashSet<MirStmt>,
) {
    fun isBorrowed(l: MirLocal): Boolean = l.id in borrowed
}

/** Counters of what the pass decided (tests, `docs/benchmarks.md`). */
class RcStats {
    var functions = 0
    var refLocals = 0
    var borrowedLocals = 0
    var moves = 0
    var takes = 0
    var ownedParams = 0
}

class RcPlan internal constructor(
    private val byFunction: HashMap<String, RcFunctionPlan>,
    private val owned: HashMap<String, BooleanArray>,
    val stats: RcStats,
) {
    fun of(f: MirFunction): RcFunctionPlan? = byFunction[f.cName]

    /**
     * Ownership convention of the parameters of function [cName] (constructor initialisers): where true the
     * callee owns the reference it receives (the caller passes a reference it owns: a moved local or a fresh
     * retain), everywhere else parameters are borrowed. Null when there is no owned parameter.
     */
    fun ownedMask(cName: String): BooleanArray? = owned[cName]
}

object RcOpt {
    fun analyze(module: MirModule): RcPlan {
        val fx = RcEffects(module).run()
        val owned = HashMap<String, BooleanArray>()
        // Which constructor parameters does the initialiser always hand over (store into a field, pass on to
        // another constructor)? Probing assumes every reference parameter is owned; more known callee conventions
        // can only add parameters, so a few rounds reach the fixpoint (delegating constructors).
        var changed = true
        var round = 0
        while (changed && round < 4) {
            changed = false
            round++
            for (f in module.functions) {
                if (f.constructorOf == null) continue
                val probe = RcAnalyzer(f, fx, RcStats(), owned, true)
                probe.run()
                val mask = probe.consumedParams()
                if (!sameMask(owned[f.cName], mask)) {
                    if (anyTrue(mask)) owned[f.cName] = mask else owned.remove(f.cName)
                    changed = true
                }
            }
        }
        val plans = HashMap<String, RcFunctionPlan>()
        val stats = RcStats()
        for (f in module.functions) {
            stats.functions++
            val plan = RcAnalyzer(f, fx, stats, owned, false).run()
            if (plan != null) plans[f.cName] = plan
        }
        return RcPlan(plans, owned, stats)
    }

    private fun anyTrue(a: BooleanArray): Boolean {
        for (x in a) if (x) return true
        return false
    }

    private fun sameMask(a: BooleanArray?, b: BooleanArray): Boolean {
        if (a == null) return !anyTrue(b)
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
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
    private val objectCtors = HashMap<String, String>()

    fun run(): RcEffects {
        for (c in module.classes) {
            val oc = c.objectCtor
            if (oc != null) objectCtors[c.cName] = oc
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
        // the first access of an object runs its constructor
        is MirExpr.ObjectGet -> objectCtors[e.classCName]?.let { it in mutating } ?: false
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
        return o is MirExpr.Local && o.local.id == f.params[0].id
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
private class Chain(val roots: ArrayList<MirLocal>, var hasField: Boolean) {
    /** The value may not come from a root (constant, singleton, a `as?` that can give NULL): moving the root would lose a reference. */
    var rootless = false
}

private class LocalInfo(val local: MirLocal, val index: Int, val isParam: Boolean) {
    var firstDef = -1
    var declScope = 0
    var declDepth = 0
    var ownedDef = false
    var duplicate = false
    /** Declared without a value (assigned later): NULL on some paths. */
    var declaredEmpty = false
    val chains = ArrayList<Chain>()
    val usePos = ArrayList<Int>()
    val useLoop = ArrayList<LoopRange?>()
    val assignPos = ArrayList<Int>()
    val assignLoop = ArrayList<LoopRange?>()
    var borrowed = false
    /** A parameter with the owned convention: handled like an owned local from the function start on. */
    var ownedParam = false
    /** The statement list whose scope declares the local (the function body for parameters). */
    var declList: List<MirStmt>? = null
    // derived by the validation
    val roots = ArrayList<LocalInfo>()
    var viaField = false
    var rootless = false
}

private class ListCtx(val list: List<MirStmt>, val loop: MirLabel?, val noClose: Boolean) {
    var index = 0
}

/**
 * [calleeOwned]: ownership conventions of the constructors (see [RcPlan.ownedMask]). [probe] analyses a
 * constructor assuming all its reference parameters (but `this`) are owned, to find out which ones it consumes.
 */
private class RcAnalyzer(
    val f: MirFunction,
    val fx: RcEffects,
    val stats: RcStats,
    val calleeOwned: HashMap<String, BooleanArray>,
    val probe: Boolean,
) {
    private val infos = ArrayList<LocalInfo>()
    private val byId = HashMap<Int, LocalInfo>()
    private var pos = 0
    private val events = ArrayList<Int>()
    private val loops = ArrayList<LoopRange>()
    private val scopeParent = ArrayList<Int>()
    private var scope = 0
    private val moves = HashMap<MirStmt, MirLocal>()
    private val takes = HashMap<MirExpr, MirLocal>()
    private val dead = HashMap<MirStmt, ArrayList<MirLocal>>()
    private var curList: List<MirStmt> = f.body
    private val consumedTop = HashSet<Int>()
    private val fresh = HashSet<MirStmt>()
    private val selfTakes = HashSet<MirStmt>()

    /** Probe result: which parameters are consumed by a top-level statement of the function. */
    fun consumedParams(): BooleanArray {
        val r = BooleanArray(f.params.size)
        for ((k, p) in f.params.withIndex()) if (p.id in consumedTop) r[k] = true
        return r
    }

    fun run(): RcFunctionPlan? {
        scopeParent += -1
        val mask = if (probe) null else calleeOwned[f.cName]
        for ((k, p) in f.params.withIndex()) {
            if (!p.type.isRef) continue
            val i = info(p, true)
            i.declList = f.body
            i.ownedParam = if (probe) f.constructorOf != null && k >= 1 else mask != null && k < mask.size && mask[k]
        }
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
        val ownedParams = ArrayList<MirLocal>()
        for (i in infos) if (i.isParam && i.ownedParam && !probe) ownedParams += i.local
        stats.refLocals += infos.size
        stats.borrowedLocals += borrowedCount
        stats.moves += moves.size
        stats.takes += takes.size
        stats.ownedParams += ownedParams.size
        if (!probe && f.constructorOf != null && f.params.isNotEmpty()) findFreshStores()
        if (borrowedIds.isEmpty() && moves.isEmpty() && takes.isEmpty() && ownedParams.isEmpty() && fresh.isEmpty()) return null
        return RcFunctionPlan(borrowedIds, moves, takes, ownedParams, dead, fresh, selfTakes)
    }

    // ---- stores into a fresh object ------------------------------------------------------------------------

    /** True when [e] uses [self] other than as the object of a field read. */
    private fun thisLeaks(e: MirExpr, self: MirLocal): Boolean {
        if (e is MirExpr.Local) return e.local.id == self.id
        if (e is MirExpr.FieldGet) {
            val o = e.obj
            if (o is MirExpr.Local && o.local.id == self.id) return false
        }
        for (c in mirChildren(e)) if (thisLeaks(c, self)) return true
        return false
    }

    /**
     * The first reference store into each field of `this` in the top-level statements of a constructor, as long as
     * `this` has not been handed to any other code, overwrites the NULL of the zeroed fresh object: nothing to release.
     */
    private fun findFreshStores() {
        val self = f.params[0]
        val stored = ArrayList<String>()
        for (s in f.body) {
            if (s is MirStmt.SetField) {
                val o = s.obj
                if (o is MirExpr.Local && o.local.id == self.id) {
                    if (thisLeaks(s.value, self)) return
                    if (s.fieldType.isRef) {
                        val key = s.owner + "#" + s.field
                        if (stored.none { it == key }) fresh += s
                        stored += key
                    }
                    continue
                }
                if (thisLeaks(s.obj, self) || thisLeaks(s.value, self)) return
                continue
            }
            val leaks = when (s) {
                is MirStmt.Declare -> s.init != null && thisLeaks(s.init, self)
                is MirStmt.Eval -> thisLeaks(s.expr, self)
                is MirStmt.Assign -> thisLeaks(s.value, self)
                else -> true // control flow may store fields we do not track
            }
            if (leaks) return
        }
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
        is MirExpr.FieldGet -> directLocal(e.obj) // a value read from a field of the local is used by the call, too
        else -> null
    }

    private fun chainOf(e: MirExpr, acc: Chain): Boolean = when (e) {
        is MirExpr.Local -> {
            acc.roots += e.local
            true
        }
        is MirExpr.Cast -> {
            if (e.mode == MirExpr.CastMode.Safe) acc.rootless = true
            chainOf(e.operand, acc)
        }
        is MirExpr.NotNull -> chainOf(e.operand, acc)
        is MirExpr.FieldGet -> {
            acc.hasField = true
            e.type.isRef && chainOf(e.obj, acc)
        }
        is MirExpr.ObjectGet, is MirExpr.StringConst, is MirExpr.NullConst -> {
            acc.rootless = true
            true
        }
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
            is MirExpr.ObjectGet -> {
                val p = pos++
                if (fx.callMutates(e)) events += p
            }
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
        val savedList = curList
        scope = scopeParent.size - 1
        curList = list
        for (s in list) stmt(s)
        curList = savedList
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
                    i.declList = curList
                    if (init != null) addDef(i, init, pos++) else i.declaredEmpty = true
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
                val savedList = curList
                scope = scopeParent.size - 1
                curList = s.body
                for (b in s.body) stmt(b)
                curList = s.condStmts
                for (c in s.condStmts) stmt(c)
                expr(s.cond)
                curList = savedList
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
        x.rootless = false
        val seen = ArrayList<LocalInfo>()
        seen += x
        return walk(x, i = x, seen = seen)
    }

    private fun walk(x: LocalInfo, i: LocalInfo, seen: ArrayList<LocalInfo>): Boolean {
        var ok = true
        if (i.declaredEmpty) x.rootless = true
        for (c in i.chains) {
            if (c.hasField) x.viaField = true
            if (c.rootless) x.rootless = true
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
            if (r.isParam && !r.ownedParam) {
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
        if (e is MirExpr.Local) return if (e.local.id == l.id) 1 else 0
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

    private val ctxStack = ArrayList<ListCtx>()

    private fun liveList(list: List<MirStmt>, out: RcBits, record: Boolean, loop: MirLabel? = null, noClose: Boolean = false): RcBits {
        val ctx = ListCtx(list, loop, noClose)
        ctxStack += ctx
        var cur = out
        for (k in list.indices.reversed()) {
            ctx.index = k
            cur = liveStmt(list[k], cur, record)
        }
        ctxStack.removeAt(ctxStack.size - 1)
        return cur
    }

    private fun containsContinue(s: MirStmt, label: MirLabel): Boolean = when (s) {
        is MirStmt.Continue -> s.label === label
        is MirStmt.If -> s.thenBody.any { containsContinue(it, label) } || s.elseBody.any { containsContinue(it, label) }
        is MirStmt.Block -> s.body.any { containsContinue(it, label) }
        is MirStmt.While -> s.body.any { containsContinue(it, label) }
        is MirStmt.Loop -> s.body.any { containsContinue(it, label) }
        is MirStmt.DoWhile -> s.body.any { containsContinue(it, label) } || s.condStmts.any { containsContinue(it, label) }
        is MirStmt.ForRange -> s.body.any { containsContinue(it, label) }
        is MirStmt.Try -> s.body.any { containsContinue(it, label) } || s.catches.any { c -> c.body.any { containsContinue(it, label) } }
        else -> false
    }

    /**
     * True when giving away the reference of [x] in the current statement leaves it dead on every path to every
     * later exit of its scope, so the emitter can skip clearing and releasing it: the statement is a direct child of
     * the list that declares [x], [x] is never reassigned, and no `continue` can jump past the statement to the
     * end of the loop body that releases the scope.
     */
    private fun closed(x: LocalInfo): Boolean {
        if (ctxStack.isEmpty()) return false
        val ctx = ctxStack[ctxStack.size - 1]
        if (ctx.noClose || x.declList !== ctx.list || x.duplicate || x.assignPos.isNotEmpty()) return false
        val loop = ctx.loop
        if (loop != null) {
            for (k in 0 until ctx.index) if (containsContinue(ctx.list[k], loop)) return false
        }
        return true
    }

    private fun noteConsumed(x: LocalInfo, s: MirStmt) {
        if (x.isParam && ctxStack.isNotEmpty() && ctxStack[ctxStack.size - 1].list === f.body) consumedTop += x.local.id
        if (closed(x)) {
            var l = dead[s]
            if (l == null) {
                l = ArrayList()
                dead[s] = l
            }
            l.add(x.local)
        }
    }

    private fun usable(x: LocalInfo): Boolean = (!x.isParam || x.ownedParam) && !x.borrowed && !x.duplicate

    /**
     * The owned local whose reference a use of [l] can take over: [l] itself, or the single owned root of a plain
     * copy (`val t = o`), which always holds the same value as the owned local while it is valid.
     */
    private fun consumable(l: MirLocal): LocalInfo? {
        val x = byId[l.id] ?: return null
        if (x.borrowed) {
            if (x.viaField || x.rootless || x.roots.size != 1) return null
            val r = x.roots[0]
            return if (usable(r)) r else null
        }
        return if (usable(x)) x else null
    }

    /** Number of reads of [l], directly or through borrowed aliases rooted at it, in [e]. */
    private fun countDeep(e: MirExpr, l: LocalInfo): Int {
        if (e is MirExpr.Local) {
            val i = byId[e.local.id] ?: return 0
            if (i === l) return 1
            if (i.borrowed && i.roots.any { it === l }) return 1
            return 0
        }
        var n = 0
        for (c in mirChildren(e)) n += countDeep(c, l)
        return n
    }

    /** Records takes for the arguments of calls in [exprs] (owned parameters of constructors) that are last uses. */
    private fun scanCalls(s: MirStmt, exprs: List<MirExpr>, e: MirExpr, out: RcBits, conditional: Boolean) {
        when (e) {
            is MirExpr.Conditional -> {
                scanCalls(s, exprs, e.cond, out, conditional)
                scanCalls(s, exprs, e.thenValue, out, true)
                scanCalls(s, exprs, e.elseValue, out, true)
            }
            is MirExpr.Binary -> {
                scanCalls(s, exprs, e.left, out, conditional)
                scanCalls(s, exprs, e.right, out, conditional || e.op == BinaryOp.AndAnd || e.op == BinaryOp.OrOr)
            }
            else -> {
                for (c in mirChildren(e)) scanCalls(s, exprs, c, out, conditional)
                if (conditional) return
                if (e is MirExpr.Call) {
                    val mask = calleeOwned[e.cName]
                    if (mask != null) for (k in e.args.indices) if (k < mask.size && mask[k]) tryTake(s, exprs, e.args[k], out)
                } else if (e is MirExpr.New) {
                    val mask = calleeOwned[e.ctorCName]
                    if (mask != null) for (k in e.args.indices) if (k + 1 < mask.size && mask[k + 1]) tryTake(s, exprs, e.args[k], out)
                }
            }
        }
    }

    private fun tryTake(s: MirStmt, exprs: List<MirExpr>, arg: MirExpr, out: RcBits) {
        val l = movable(arg) ?: return
        val x = consumable(l) ?: return
        if (out.get(x.index)) return
        var n = 0
        for (e in exprs) n += countDeep(e, x)
        if (n != 1) return
        takes[arg] = x.local
        if (s is MirStmt.Assign && s.local.id == x.local.id) selfTakes += s
        noteConsumed(x, s)
    }

    private fun takesIn(s: MirStmt, exprs: List<MirExpr>, out: RcBits, record: Boolean) {
        if (!record || calleeOwned.isEmpty()) return
        for (e in exprs) scanCalls(s, exprs, e, out, false)
    }

    /** Records a move when [s] consumes the last use of an owned local. [out] is the live set after [s]. */
    private fun consume(s: MirStmt, value: MirExpr?, out: RcBits, target: MirLocal?, record: Boolean) {
        if (!record) return
        val l = movable(value) ?: return
        val x = consumable(l) ?: return
        if (out.get(x.index)) return
        if (target != null) {
            val t = byId[target.id] ?: return
            if (t.borrowed || t === x) return
        }
        if (value == null || countDeep(value, x) != 1) return
        if (s is MirStmt.SetField && (countDeep(s.obj, x) != 0)) return
        // a take of the same local in this statement (an argument) would count as a second use
        moves[s] = x.local
        noteConsumed(x, s)
    }

    private fun liveStmt(s: MirStmt, out0: RcBits, record: Boolean): RcBits {
        val exc = excLive
        val out = if (exc == null) out0 else out0.copy().also { it.orWith(exc) }
        when (s) {
            is MirStmt.Declare -> {
                val init = s.init
                consume(s, init, out, s.local, record)
                if (init != null) takesIn(s, listOf(init), out, record)
                val inn = out.copy()
                byId[s.local.id]?.let { inn.clear(it.index) }
                if (init != null) addUses(init, inn)
                return inn
            }
            is MirStmt.Assign -> {
                consume(s, s.value, out, s.local, record)
                // the old value of the assigned local dies at the assignment: it may be handed to a callee in the value
                val outTake = out0.copy()
                byId[s.local.id]?.let { outTake.clear(it.index) }
                if (exc != null) outTake.orWith(exc)
                takesIn(s, listOf(s.value), outTake, record)
                val inn = out.copy()
                byId[s.local.id]?.let { inn.clear(it.index) }
                addUses(s.value, inn)
                return inn
            }
            is MirStmt.SetField -> {
                if (s.fieldType.isRef) consume(s, s.value, out, null, record)
                takesIn(s, listOf(s.obj, s.value), out, record)
                val inn = out.copy()
                addUses(s.obj, inn)
                addUses(s.value, inn)
                return inn
            }
            is MirStmt.Eval -> {
                takesIn(s, listOf(s.expr), out, record)
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
                    val bodyIn = liveList(s.body, head, false, s.label)
                    val next = head.copy()
                    next.orWith(bodyIn)
                    if (next.sameAs(head)) break
                    head = next
                }
                contLive[s.label.id] = head
                if (record) liveList(s.body, head, true, s.label)
                return head
            }
            is MirStmt.Loop -> {
                breakLive[s.label.id] = out
                var head = empty()
                while (true) {
                    contLive[s.label.id] = head
                    val bodyIn = liveList(s.body, head, false, s.label)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                contLive[s.label.id] = head
                if (record) liveList(s.body, head, true, s.label)
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
                    condStart = liveList(s.condStmts, afterCond, false, null, true)
                    contLive[s.label.id] = condStart
                    val bodyIn = liveList(s.body, condStart, false, null, true)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                if (record) {
                    val afterCond = condUses.copy()
                    afterCond.orWith(head)
                    afterCond.orWith(out)
                    condStart = liveList(s.condStmts, afterCond, true, null, true)
                    contLive[s.label.id] = condStart
                    liveList(s.body, condStart, true, null, true)
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
                    val bodyIn = liveList(s.body, after, false, s.label)
                    if (bodyIn.sameAs(head)) break
                    head = bodyIn
                }
                val after = head.copy()
                after.orWith(out)
                contLive[s.label.id] = after
                if (record) liveList(s.body, after, true, s.label)
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
                s.value?.let { takesIn(s, listOf(it), empty(), record) }
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
