package mini.backend.memory

/** Facts about the unit (function or escaping lambda) being analysed. */
internal class UnitInfo(
    val fnName: String,
    val retOwned: Boolean,
    val retTracked: Boolean,
    val noAlloc: Boolean,
    /** Ids of locals declared in the unit body (arena locals are only "local" if declared here). */
    val declared: Set<Int>,
    val names: Map<Int, String>,
    val isLambda: Boolean,
    val fn: MFun? = null,
    /** Parameter slot id -> index in [MFun.params]. */
    val paramIndex: Map<Int, Int> = emptyMap(),
    /** Ids of the locals this lambda unit captures from the function around it. */
    val capturedIds: Set<Int> = emptySet(),
    /** The unit is (lowered into) a coroutine state machine: no default-allocated object may escape there (M0204). */
    val suspend: Boolean = false,
)

private const val HEAP_CLASS = "mini.memory.Heap"
private const val RC_CLASS = "mini.memory.Rc"

/**
 * Transfer functions of the dataflow analysis. One instance per unit; [st] is the current state while a
 * block is interpreted. When [reporting] is false (fixpoint iteration) nothing is recorded.
 */
internal class Interp(private val u: UnitInfo, private val sink: (Diag) -> Unit, private val queue: (MExpr.Lambda, Ctx) -> Unit) {
    var st: State = State.EMPTY
    var reporting = false

    private fun name(id: Int) = u.names[id] ?: fieldRootNames[id] ?: "value"

    private fun diag(
        code: String, pos: Pos, msg: String, label: String, help: String? = null,
        slot: Int = -1, sev: Severity = Severity.Error, secondary: List<Pair<Pos, String>> = emptyList(),
    ) {
        if (!reporting) return
        sink(Diag(code, sev, msg, pos, label, secondary, listOfNotNull(help), slot))
    }

    private fun slot(id: Int): Slot? = st.slots[id]
    private fun put(id: Int, s: Slot) { st = st.withSlot(id, s) }

    private fun isLocalArena(id: Int) = id in u.declared

    // ---- allocator resolution (docs/memory-model.md 2.2) --------------------------------------

    private sealed class AR {
        object Ambiguous : AR()
        object NoneFound : AR()
        class Found(val kind: AllocKind, val id: Int) : AR()
    }

    private fun rootId(id: Int): Int {
        val s = slot(id)
        if (s != null && s.bits and B != 0 && s.refs.size == 1) return s.refs.first()
        return id
    }

    private fun resolveAlloc(cls: MClass?, ctx: Ctx): AR {
        val ab = cls?.allocatedBy
        for (level in ctx.levels.asReversed()) {
            val cands = level.filter { it.type.tk == TK.Alloc && (ab == null || it.type.className == ab) }
            if (cands.size > 1) return AR.Ambiguous
            if (cands.size == 1) return AR.Found(cands[0].type.alloc ?: AllocKind.Other, rootId(cands[0].id))
        }
        if (ab == HEAP_CLASS) return AR.Found(AllocKind.Heap, -1)
        if (ab == RC_CLASS) return AR.Found(AllocKind.Rc, -1)
        return AR.NoneFound
    }

    private fun siteKind(k: AllocKind): SK? = when (k) {
        AllocKind.Heap -> SK.Heap
        AllocKind.Arena -> SK.Arena
        AllocKind.Pool -> SK.Pool
        AllocKind.Debug -> SK.Debug
        else -> null
    }

    // ---- state helpers ------------------------------------------------------------------------

    private fun sitesOf(id: Int): Set<Site> {
        val s = slot(id) ?: return emptySet()
        if (s.bits and B != 0) {
            val out = HashSet<Site>(s.sites)
            for (r in s.refs) slot(r)?.let { out += it.sites }
            return out
        }
        return s.sites
    }

    private fun rootsOf(id: Int): Set<Int> {
        val s = slot(id) ?: return setOf(id)
        // an owner that borrows (the object a call returned over its receiver, strict-5d): alive only while what it borrows is, and itself
        if (s.bits and K != 0 && s.bits and B == 0 && s.bits and O != 0 && s.refs.isNotEmpty()) return s.refs + id
        return if (s.bits and B != 0 && s.refs.isNotEmpty()) s.refs else setOf(id)
    }

    /** [v] is an instance of a local / anonymous class that borrows only parameters (the receiver included) of the function being analysed. */
    private fun borrowsOnlyParams(v: AVal): Boolean {
        if (!v.caps || v.refs.isEmpty() || v.src >= 0 || !v.tracked) return false
        for (r in v.refs) {
            if (r in u.declared) return false
            val s = slot(r) ?: return false
            if (s.bits and B == 0) return false
        }
        return true
    }

    /** Use of slot [id] (read, call, borrow): M0101 / M0104 when the owner is gone. */
    private fun checkUse(id: Int, pos: Pos) {
        for (r in rootsOf(id)) {
            val s = slot(r) ?: continue
            if (s.bits and (F or M) == 0) continue
            val maybe = s.bits and (F or M).inv() and (O or N or B or X) != 0
            if (r < 0) {
                // a loan of an element of a collection: the collection was changed (or ended) since the element was read
                val cn = name(pseudoColl[r] ?: id)
                diag(
                    "M0101", pos, "use of `${name(id)}` after the collection it was read from changed",
                    if (maybe) "an element read from `$cn` may be gone: `$cn` may have been changed before" else "an element read from `$cn` is gone: `$cn` was changed before",
                    "read the element again after the change, or copy what you need out of it first",
                    slot = r,
                )
                put(r, Slot((s.bits and (F or M).inv()) or X, s.refs, s.sites, s.vpos))
                continue
            }
            val nm = name(r)
            if (s.bits and F != 0) {
                diag(
                    "M0101", pos, "use of `$nm` after it was freed", if (maybe) "`$nm` may have been freed here" else "`$nm` was freed before",
                    "do not use `$nm` after `$nm.free()`; move the free to the end or keep the value alive",
                    slot = r,
                )
            } else {
                diag(
                    "M0104", pos, "use of moved value `$nm`", if (maybe) "`$nm` may have been moved here" else "`$nm` was moved before",
                    "use the new owner instead, or write `val b = $nm` (alias) when no move is intended",
                    slot = r,
                )
            }
            put(r, Slot((s.bits and (F or M).inv()) or X, s.refs, s.sites, s.vpos))
        }
    }

    /** Whether a value of the default allocator is among [sites]. */
    private fun hasDefault(sites: Set<Site>): Boolean {
        for (s in sites) if (s.kind == SK.Default) return true
        return false
    }

    /** The default-allocated objects of [sites] get an owner: their allocation is not a temporary of the statement (the object is released by whoever owns it). */
    private fun markOwned(sites: Set<Site>) {
        if (!reporting) return
        for (s in sites) if (s.kind == SK.Default && s.id >= 0) newNodes[s.id].escapes = true
    }

    private val newNodes = ArrayList<MExpr.New>()
    private val newIds = HashMap<MExpr.New, Int>()
    private fun siteIdOf(e: MExpr.New): Int {
        val k = newIds[e]
        if (k != null) return k
        newNodes.add(e)
        val id = newNodes.size - 1
        newIds[e] = id
        return id
    }

    /** A local that owns a default-allocated object: the code generation releases it at the end of its scope. */
    private fun markDropOwner(l: MLocal) {
        if (reporting) l.dropOwner = true
    }

    /** The read [v] moves the owner out of its local: the code generation sets the local to null. */
    private fun markMove(v: AVal) {
        if (reporting) v.node?.moves = true
    }

    private fun discard(v: AVal) {
        if (!v.fresh) return
        // an owned default-allocated result of a call that nobody binds is released after its statement
        if (reporting && v.call != null && hasDefault(v.sites)) v.call.ownedTemp = true
        for (s in v.sites) if (s.kind.obligation && s.kind != SK.Unknown) {
            diag(
                "M0102", s.pos, "allocation from `${s.kind.label}` is never released", "allocated here; path: result discarded",
                "bind the result and call `.free()` on it, or pass it to an `@Owned` parameter",
            )
        }
    }

    private fun escape(v: AVal) {
        if (hasDefault(v.sites)) {
            if (u.suspend) {
                for (s in v.sites) if (s.kind == SK.Default) {
                    diag(
                        "M0204", s.pos, "no allocator for an escaping Strict value", "a default-allocated object cannot leave a coroutine body",
                        "add `context(h: Heap)` to the function or `@AllocatedBy(Heap::class)` to the class",
                    )
                }
                return
            }
            markOwned(v.sites)
        }
        for (s in v.sites) {
            if (u.noAlloc && s.kind != SK.Param && s.kind != SK.Field && s.kind != SK.Unknown) {
                diag("M0205", s.pos, "allocation in `@NoAlloc` function `${u.fnName}`", "allocates here", "remove `@NoAlloc` or avoid the escaping allocation")
            }
        }
    }

    /** An arena object of a *local* arena flows out of the arena's scope: M0105. [leaves]: which arena ids leave. */
    private fun arenaEscape(v: AVal, leaves: (Int) -> Boolean): Boolean {
        for (s in v.sites) {
            if (s.kind == SK.Arena && isLocalArena(s.id) && leaves(s.id)) {
                diag(
                    "M0105", v.pos, "value allocated in arena `${name(s.id)}` escapes its scope", "escapes the scope of the arena here",
                    "return a value without allocation (e.g. an `Int`), or move the arena into the caller (`context(a: Arena) fun ...`)",
                )
                return true
            }
        }
        return false
    }

    // ---- ownership transfer -------------------------------------------------------------------

    /** Hands [v] to a new owner (owned parameter, return, owning field, thread). False when it is a borrow (M0109 reported). */
    private fun takeOwnership(v: AVal, whatFor: String): Boolean {
        if (!v.tracked || v.isNull) return true
        if (v.caps && v.refs.isNotEmpty()) {
            // the object of a local / anonymous class that borrows the receiver or a parameter and is returned as it is made: the caller owns the object
            // (reference counted) and holds a loan of what it borrows (the summary's `carries`), strict-5d
            if (whatFor == "returned as owned" && borrowsOnlyParams(v)) { escape(v); return true }
            escapeClosure(v, "is $whatFor")
            return false
        }
        if (v.fresh) { escape(v); return true }
        if (v.src >= 0) {
            val s = slot(v.src) ?: return true
            if (s.bits and B != 0) {
                diag(
                    "M0109", v.pos, "borrowed `${name(v.src)}` is $whatFor", "`${name(v.src)}` is only borrowed",
                    "declare the parameter `@Owned` (and move at the call), or use `.move()` on a real owner",
                    slot = rootsOf(v.src).firstOrNull() ?: -1,
                )
                return false
            }
            escape(v)
            markMove(v)
            put(v.src, Slot(M, s.refs, s.sites, s.vpos))
            return true
        }
        if (v.refs.isNotEmpty()) {
            diag(
                "M0109", v.pos, "borrowed value is $whatFor", "this value is borrowed from `${name(v.refs.first())}`",
                "make the callee/field/return `@Owned`, or return `@Borrowed`",
                slot = v.refs.first(),
            )
            return false
        }
        return true
    }

    // ---- expressions --------------------------------------------------------------------------

    fun eval(e: MExpr, env: Env): AVal = when (e) {
        is MExpr.Opaque -> {
            for (c in e.children) discard(eval(c, env))
            AVal.untracked(e.type, e.pos, e.strLiteral)
        }
        is MExpr.Null -> AVal(e.type, false, -1, emptySet(), emptySet(), true, true, e.pos)
        is MExpr.GetGlobal -> AVal.untracked(e.type, e.pos)
        is MExpr.Local -> readLocal(e)
        is MExpr.New -> evalNew(e, env)
        is MExpr.Call -> evalCall(e, env)
        is MExpr.FieldGet -> evalFieldGet(e, env)
        is MExpr.NullTest -> { discard(eval(e.operand, env)); AVal.untracked(MType.PRIM, e.pos) }
        is MExpr.Not -> { discard(eval(e.operand, env)); AVal.untracked(MType.PRIM, e.pos) }
        is MExpr.Free -> evalFree(e, env)
        is MExpr.Move -> evalMove(e, env)
        is MExpr.RawAlloc -> {
            discard(eval(e.alloc, env))
            if (u.noAlloc) diag("M0205", e.pos, "allocation in `@NoAlloc` function `${u.fnName}`", "calls `allocate` here", "remove `@NoAlloc` or avoid the allocation")
            AVal.untracked(e.type, e.pos)
        }
        is MExpr.Lambda -> evalLambda(e, env)
    }

    private fun readLocal(e: MExpr.Local): AVal {
        val id = e.local.id
        val t = e.type
        val s = slot(id)
        if (s == null) return AVal.untracked(t, e.pos)
        if (t.holds && s.bits and C != 0) {
            // a collection of tracked values: reading it is a use (its owner may be gone); what it lends out is checked at the element reads
            checkUse(id, e.pos)
            return AVal(t, false, id, rootsOf(id), emptySet(), false, false, e.pos, node = e)
        }
        if (s.bits and K != 0) {
            // a closure: every use needs the owners it borrows from to be alive (a call of a function value has no position of its own)
            checkUse(id, if (e.pos.line > 0) e.pos else s.vpos)
            val c = slot(id) ?: s
            return AVal(t, false, id, rootsOf(id), if (t.tracked) sitesOf(id) else c.sites, false, false, c.vpos, caps = true, node = e)
        }
        if (!t.tracked) return AVal.untracked(t, e.pos)
        checkUse(id, e.pos)
        val s2 = slot(id)!!
        val refs = rootsOf(id)
        val sites = sitesOf(id)
        return AVal(t, false, id, refs, sites, s2.bits and N != 0 || t.nullable && s2.bits and (O or B) == 0, false, e.pos, node = e)
    }

    private fun evalFieldGet(e: MExpr.FieldGet, env: Env): AVal {
        val ov = eval(e.obj, env)
        discard(ov)
        val o = e.obj
        if (o is MExpr.Local) {
            val fs = st.fields[FKey(o.local.id, e.field.name)]
            if (fs != null && fs.bits and FM != 0 && fs.bits and (FU or FN or FO) == 0) {
                diag("M0104", e.pos, "use of moved field `${o.local.name}.${e.field.name}`", "moved out before", "restore the field with an assignment first")
            }
        }
        if (!e.type.tracked) {
            // strict-fieldcoll: a collection of tracked values held in a field of a Strict object (`n.children`): the elements read from it are loans of that field,
            // which every mutating call on it, the assignment of the field and a call of user code on the object end (the owners of the object stay alive too)
            if (e.type.holds && ov.type.tracked) {
                val owners = ownerIds(ov)
                if (owners.isNotEmpty()) return AVal(e.type, false, -1, owners.mapTo(LinkedHashSet()) { fieldRoot(it, e.field.name) }, emptySet(), false, false, e.pos)
            }
            return AVal.untracked(e.type, e.pos)
        }
        val roots = if (ov.refs.isNotEmpty()) ov.refs else if (ov.src >= 0) setOf(ov.src) else emptySet()
        return AVal(e.type, false, -1, roots, ov.sites, true, false, e.pos)
    }

    // ---- strict-fieldcoll: collections held in fields of Strict objects ------------------------------------------

    private val fieldRootIds = HashMap<Pair<Int, String>, Int>()
    private val fieldRootInfo = HashMap<Int, Pair<Int, String>>()
    private val fieldRootNames = HashMap<Int, String>()

    /** The pseudo root (negative id, stable across the passes of a loop) of the collection in field [field] of the owner slot [owner]. */
    private fun fieldRoot(owner: Int, field: String): Int {
        val key = owner to field
        fieldRootIds[key]?.let { return it }
        val id = -(1 shl 30) - fieldRootIds.size
        fieldRootIds[key] = id
        fieldRootInfo[id] = key
        fieldRootNames[id] = "${u.names[owner] ?: fieldRootNames[owner]?.let { "an element of $it" } ?: "value"}.$field"
        return id
    }

    /**
     * The identity of the object [v] stands for, for the field roots: the element loans it is (an object read from a collection is told apart from the object
     * that holds the collection: `n = n.children[c]` walks down a tree) and the real owner slots it borrows from or is.
     */
    private fun ownerIds(v: AVal): Set<Int> {
        val base = if (v.refs.isNotEmpty()) v.refs else if (v.src >= 0) setOf(v.src) else emptySet()
        val out = LinkedHashSet<Int>()
        for (r in base) if (r >= 0 || r in pseudoColl) out += r
        return out
    }

    /** Ends the loans of the elements of every collection held in a field of the object [v] (user code may change them). */
    private fun killFieldLoansOf(v: AVal) {
        if (fieldRootInfo.isEmpty() || !v.type.tracked || v.fresh) return
        val owners = ownerIds(v)
        if (owners.isEmpty()) return
        val ids = fieldRootInfo.filter { it.value.first in owners }.keys
        if (ids.isNotEmpty()) killLoans(ids)
    }

    private fun evalNew(e: MExpr.New, env: Env): AVal {
        val r = evalNew0(e, env)
        if (e.captures.isEmpty()) return r
        val (refs, arena) = captureOf(e.captures, e.pos)
        if (refs.isEmpty()) return r
        // an instance of a local / anonymous class that borrows what it captures: a closure like a lambda
        return AVal(r.type, false, -1, refs, arena + r.sites, false, false, e.pos, caps = true)
    }

    private fun evalNew0(e: MExpr.New, env: Env): AVal {
        val cls = e.cls
        val fn = e.ctor
        val args = processArgs(fn, null, e.args, env, e, ctor = true)
        val t = e.type
        if (t.tk == TK.Alloc) {
            if (t.isResource) {
                return AVal(t, true, -1, emptySet(), setOf(Site(e.pos, SK.Resource, -1)), false, false, e.pos)
            }
            return AVal.untracked(t, e.pos)
        }
        if (cls == null || cls.mode == MemMode.Managed || t.tk != TK.Strict) return AVal.untracked(t, e.pos)
        val site: Site
        when (val r = resolveAlloc(cls, env.ctx)) {
            is AR.Ambiguous -> {
                diag(
                    "M0114", e.pos, "ambiguous allocator for `${cls.name.substringAfterLast('.')}`", "several allocators are visible at the same level",
                    "wrap the allocation in a nested `context(x) { ... }` block to choose one",
                )
                return AVal.untracked(t, e.pos)
            }
            is AR.NoneFound -> site = Site(e.pos, SK.Default, siteIdOf(e))
            is AR.Found -> {
                val k = siteKind(r.kind) ?: return AVal.untracked(t, e.pos)
                site = Site(e.pos, k, r.id)
                if (k == SK.Arena && env.loopArenas.contains(r.id)) {
                    diag(
                        "M0202", e.pos, "allocation in a loop inside arena `${name(r.id)}`: unbounded growth", "allocates in every iteration",
                        "hoist the allocation out of the loop or reset/recreate the arena per iteration", sev = Severity.Warning,
                    )
                }
            }
        }
        // arguments flowing into owning fields of the new object
        val fieldInit = HashMap<String, Int>()
        for (f in cls.fields) if (f.type.tracked && !f.borrowed) fieldInit[f.name] = FN
        for ((j, p) in fn.params.withIndex()) {
            val f = cls.fields.firstOrNull { it.name == p.local.name } ?: continue
            val a = args.getOrNull(j) ?: continue
            if (f.borrowed || p.annot.borrowed != null) continue
            checkOwnedInto(setOf(site), a, f)
            if (f.type.tracked) fieldInit[f.name] = if (a.isNull) FN else (FO or if (a.nullable) FN else 0)
        }
        return AVal(t, true, -1, emptySet(), setOf(site), false, false, e.pos, initFields = fieldInit)
    }

    /** M0115 (arena object would own a foreign reference) / M0105 (arena value stored in a non-arena object). */
    private fun checkOwnedInto(targetSites: Set<Site>, v: AVal, f: MField): Boolean {
        if (v.isNull) return true
        val arenaTarget = targetSites.firstOrNull { it.kind == SK.Arena }
        if (arenaTarget != null) {
            if (v.sites.any { it.kind == SK.Arena && it.id != arenaTarget.id } &&
                arenaEscape(v) { id -> id != arenaTarget.id }
            ) {
                return false
            }
            val bad = when (v.type.tk) {
                TK.Str -> !v.strLit
                TK.Managed -> true
                TK.Strict, TK.Alloc -> v.sites.any { it.kind != SK.Arena && it.kind != SK.Param && it.kind != SK.Field }
                    || v.sites.any { it.kind == SK.Arena && it.id != arenaTarget.id }
                else -> false
            }
            if (bad) {
                diag(
                    "M0115", v.pos, "arena object `${f.owner.name.substringAfterLast('.')}` would own a foreign reference in field `${f.name}`; the arena never releases it",
                    "flows into an owning field of an arena object",
                    "declare the field `@Borrowed`, or allocate both objects outside the arena",
                )
                if (v.src >= 0) slot(v.src)?.let { s -> if (s.bits and B == 0) put(v.src, Slot(M, s.refs, s.sites, s.vpos)) }
                return false
            }
            return true
        }
        // target is not an arena object: arena values must not be stored into it
        if (v.sites.isNotEmpty()) {
            val targetIds = targetSites.filter { it.kind == SK.Arena }.map { it.id }.toSet()
            if (arenaEscape(v) { id -> id !in targetIds }) return false
        }
        return true
    }

    private fun processArgs(fn: MFun, recv: AVal?, args: List<MExpr>, env: Env, at: MExpr, ctor: Boolean = false): List<AVal> {
        val out = ArrayList<AVal>()
        val foreign = fn.mode == MemMode.Managed || fn.external
        for ((i, a) in args.withIndex()) {
            val v = eval(a, env)
            out += v
            val p = fn.params.getOrNull(i)
            if (v.caps && v.refs.isNotEmpty() && p != null && !p.isContext) {
                val fnName = fn.qname.substringAfterLast('.')
                val why = when {
                    p.annot.borrowed != null || fn.borrowsAll -> null
                    p.owned -> if (ctor) "is stored into a field of the new object" else "is consumed by `$fnName` (`@Owned` parameter `${p.local.name}`)"
                    else -> Summaries.escapeReason(fn, i)?.let { "is kept by `$fnName`, whose parameter `${p.local.name}` $it" }
                }
                if (why != null) { if (v.child) escapeChild(v, why) else escapeClosure(v, why); continue }
                if (!v.tracked) { arenaEscapeArg(v); continue }
            }
            if (p == null || p.isContext) { if (!v.tracked) arenaEscapeArg(v); continue }
            if (!v.tracked) { arenaEscapeArg(v); continue }
            if (foreign) {
                val why = if (p.annot.borrowed == null && !fn.borrowsAll) Summaries.escapeReason(fn, i) else null
                if (why != null && countsReferences(fn) && ownedByDefault(v)) {
                    // an owner of the default allocator handed to a collection, a vararg array or a pair: the keeper retains its own count of the
                    // reference counted object (the owner keeps its count and releases it when it ends), so nothing is moved and nothing dangles
                } else if (why != null) {
                    val fnName = fn.qname.substringAfterLast('.')
                    diag(
                        "M0110", v.pos, "Strict value handed to Managed code that keeps it",
                        "parameter `${p.local.name}` of `$fnName` $why",
                        "keep the value in Strict code and pass only what is needed (`x.field`), or mark the parameter `@Borrowed` if `$fnName` really only lends it",
                    )
                }
                discard(v)
                continue
            }
            if (p.owned) {
                if (ctor) {
                    // ownership into an owning field: handled by evalNew (field checks), here only the move
                    val f = null
                    takeOwnership(v, "stored in an owned field")
                } else if (v.sites.any { it.kind == SK.Arena }) {
                    diag(
                        "M0106", v.pos, "arena object passed to an `@Owned` parameter", "the callee could free it, but the arena owns it",
                        "pass it to a borrowed parameter instead",
                    )
                } else {
                    takeOwnership(v, "passed as an owned argument")
                }
            } else if (fn.storesParams.contains(i)) {
                // the callee stores this borrowed parameter into an owned field (reported there as M0109):
                // read as if it were `@Owned`, so the caller does not get a follow-up leak
                if (v.fresh || (v.src >= 0 && slot(v.src)?.let { it.bits and B == 0 } == true)) takeOwnership(v, "passed as an owned argument") else discard(v)
            } else {
                discard(v)
            }
        }
        return out
    }

    /** A non-tracked value (lambda) that carries arena sites flows into an unknown consumer: treat as escaping. */
    private fun arenaEscapeArg(v: AVal) {
        if (v.sites.isNotEmpty() && !v.tracked) arenaEscape(v) { true }
    }

    private fun evalCall(e: MExpr.Call, env: Env): AVal {
        val fn = e.fn
        val rv = e.receiver?.let { eval(it, env) }
        if (rv != null) {
            // a closure as the receiver (`block.startCoroutine(c)`) that the callee keeps: it outlives the call
            if (rv.caps && rv.refs.isNotEmpty() && fn.receiver?.annot?.borrowed == null && !fn.borrowsAll) {
                Summaries.escapeReason(fn, FnSummary.RECEIVER)?.let { escapeClosure(rv, "is kept by `${fn.qname.substringAfterLast('.')}`, which $it") }
            }
            discard(rv)
        }
        // exception: methods of local owners may be called while a field is moved out
        if (!(e.receiver is MExpr.Local)) checkMovedFields(e.pos)
        val args = processArgs(fn, rv, e.args, env, e)
        // strict-5b: a call that changes a collection ends every loan of one of its elements; code we cannot see into may do it, too
        if (rv != null && (fn.mutates || fn.userCode && rv.type.holds)) killLoansOf(rv)
        if (fn.userCode) for (a in args) if (a.type.holds) killLoansOf(a)
        if (fn.userCode) { if (rv != null) killFieldLoansOf(rv); for (a in args) killFieldLoansOf(a) }
        val rt = e.type
        if (rv != null && rt.holds && !rt.tracked && isView(fn, rt)) {
            // an iterator or sublist of a collection: what is read or changed through it is read or changed in the collection
            val roots = collRoots(rv)
            if (roots != null) return AVal(rt, false, -1, roots, emptySet(), false, false, e.pos)
        }
        if (rv != null && rt.holds && !rt.tracked && isEntryType(rt) && !fn.mutates) {
            // an entry read from a map (`first()`, `next()`, `get(i)` on a view of it): it is a view of the map, so the loans that are read from it end with the map's changes
            val roots = collRoots(rv)
            if (roots != null) return AVal(rt, false, -1, roots, emptySet(), false, false, e.pos)
        }
        if (rt.tracked && fn.ret.annot.borrowed == null && !u.suspend) {
            // a function that returns an object of a local / anonymous class over its receiver or parameters (FnSummary.carries): the caller owns the
            // object (it is released when the owner ends) and the object is a loan of what the arguments borrow (strict-5d)
            val carries: Set<Int> = Summaries.of(fn)?.carries ?: emptySet()
            if (carries.isNotEmpty()) {
                val refs = LinkedHashSet<Int>()
                for (i in carries) {
                    val a = if (i == FnSummary.RECEIVER) rv else args.getOrNull(i)
                    if (a != null && !a.fresh && !a.isNull) refs += a.refs
                }
                if (refs.isNotEmpty()) return AVal(rt, true, -1, refs, setOf(Site(e.pos, SK.Default, -1)), rt.nullable, false, e.pos, caps = true, call = e)
            }
        }
        if (rt.tracked && (fn.mode == MemMode.Managed || fn.external) && fn.ret.annot.owned != null && fn.ret.annot.borrowed == null && !u.suspend) {
            // `removeAt`, `set`, `remove`: the collection hands the element it held (with its count) to the caller: a new owner of the default allocator
            return AVal(rt, true, -1, emptySet(), setOf(Site(e.pos, SK.Default, -1)), true, false, e.pos, call = e)
        }
        if (rv != null && rt.tracked && (fn.mode == MemMode.Managed || fn.external || fn.ret.annot.borrowed != null)) {
            // an element of a collection (or what a Strict container lends out): a loan that the next `@Mutates` call on the container ends
            val roots = collRoots(rv)
            if (roots != null && fn.ret.annot.owned == null) return elementLoan(roots, rt, e.pos)
        }
        if (!rt.tracked) {
            // a function that returns a closure over some of its parameters (FnSummary.carries): the result borrows what those arguments borrow
            val carries: Set<Int> = Summaries.of(fn)?.carries ?: emptySet()
            if (carries.isEmpty()) return AVal.untracked(rt, e.pos)
            val refs = LinkedHashSet<Int>()
            for (i in carries) {
                // the closure the callee returns reads its receiver (`fun peek(): () -> Int = { n }`): it is a loan of the receiver
                val a = if (i == FnSummary.RECEIVER) rv else args.getOrNull(i)
                if (a != null && !a.fresh && !a.isNull) refs += a.refs
            }
            return if (refs.isEmpty()) AVal.untracked(rt, e.pos) else AVal(rt, false, -1, refs, emptySet(), false, false, e.pos, caps = true)
        }
        if ((fn.mode == MemMode.Managed || fn.external) && fn.ret.annot.borrowed == null) return AVal.untracked(rt, e.pos)
        if (fn.ret.annot.borrowed != null) {
            val refs = HashSet<Int>()
            val sites = HashSet<Site>()
            rv?.let { refs += it.refs; sites += it.sites }
            for ((i, a) in args.withIndex()) {
                val p = fn.params.getOrNull(i)
                if (p != null && !p.isContext && a.tracked && !a.fresh) { refs += a.refs; sites += a.sites }
            }
            // what a Strict container lends out is a loan that its next `@Mutates` call ends
            if (rv != null) {
                val roots = loanRoots(rv)
                if (roots != null) refs += elementLoan(roots, rt, e.pos).refs.first()
            }
            return AVal(rt, false, -1, refs, sites, true, false, e.pos)
        }
        // owned result: where does it live?
        var kind: AllocKind? = null
        var id = -1
        for ((i, p) in fn.params.withIndex()) {
            if (!p.isContext || p.local.type.tk != TK.Alloc) continue
            val a = e.args.getOrNull(i) as? MExpr.Local ?: continue
            val ab = rt.cls?.allocatedBy
            if (ab != null && a.local.type.className != ab) continue
            kind = a.local.type.alloc ?: AllocKind.Other
            id = rootId(a.local.id)
            break
        }
        if (kind == null) {
            val ab = rt.cls?.allocatedBy
            if (ab == HEAP_CLASS) { kind = AllocKind.Heap } else if (ab == RC_CLASS) kind = AllocKind.Rc
        }
        if (kind == AllocKind.Rc || kind == AllocKind.Other) return AVal.untracked(rt, e.pos)
        // no allocator visible at the call (the callee allocates, or hands on a value of its own): the default allocator, whose objects the owner releases
        val sk = if (fn.cFree != null) SK.Extern else if (kind == null) (if (u.suspend) SK.Unknown else SK.Default) else siteKind(kind) ?: SK.Unknown
        return AVal(rt, true, -1, emptySet(), setOf(Site(e.pos, sk, id)), rt.nullable, false, e.pos, call = e)
    }

    /** Library containers that count the references they keep: collections, the array of a vararg, pairs and triples (strict-5c). */
    private fun countsReferences(fn: MFun): Boolean {
        val n = fn.qname
        return n.startsWith("kotlin.collections.") || n == "kotlin.vararg" || n.startsWith("mini.internal.refArraySet") || n.startsWith("kotlin.Pair") || n.startsWith("kotlin.Triple")
    }

    /** [v] is a value of the default allocator that has an owner (fresh, or an owner variable): its object is reference counted. */
    private fun ownedByDefault(v: AVal): Boolean {
        if (v.isNull || v.sites.isEmpty()) return false
        for (s in v.sites) if (s.kind != SK.Default) return false
        if (v.src >= 0) { val sl = slot(v.src); if (sl == null || sl.bits and B != 0) return false }
        return v.refs.isEmpty() || v.src >= 0
    }

    // ---- strict-5b: loans of the elements of a collection ----------------------------------------------------

    /** Pseudo slot of an element loan (negative id) -> the collection it was read from. */
    private val pseudoColl = HashMap<Int, Int>()

    /** The collections (slots with [C], not views) [v] stands for, or null when it is no collection of tracked values. */
    private fun collRoots(v: AVal): Set<Int>? {
        if (!v.type.holds) return null
        val out = LinkedHashSet<Int>()
        for (r in v.refs) {
            if (r in fieldRootInfo) { out += r; continue }
            val s = slot(r) ?: continue
            if (s.bits and C != 0) out += r
        }
        // a `Map.Entry` local is a view of its map, not a collection of its own: what is read from it depends on the map it came from (the roots in
        // its refs), never on the variable (rebinding `val (k, v) = it.next()` in a loop changes the variable, not the map)
        if (v.src >= 0 && out.isEmpty() && !isEntryType(v.type)) { val s = slot(v.src); if (s != null && s.bits and C != 0) out += v.src }
        return if (out.isEmpty()) null else out
    }

    private fun isEntryType(t: MType): Boolean = t.className?.endsWith("Entry") == true

    private fun isView(fn: MFun, rt: MType): Boolean {
        val n = fn.qname.substringAfterLast('.')
        if (n == "iterator" || n == "listIterator" || n == "subList" || n == "asReversed") return true
        // the key, value and entry views of a map (`m.values.first()`, `m.entries.first()`): what is read through them is read from the map
        if (n == "entries" || n == "keys" || n == "values" || n == "<get-entries>" || n == "<get-keys>" || n == "<get-values>") return true
        val cn = rt.className ?: return false
        return cn.endsWith("Iterator")
    }

    /** Reading an element of a collection: a loan with a pseudo slot of its own, which every mutating call on the collection ends. */
    private fun elementLoan(roots: Set<Int>, rt: MType, pos: Pos): AVal {
        val pid = -(pos.line * 4096 + pos.col) - 1
        pseudoColl[pid] = roots.first()
        put(pid, Slot(O, roots + roots.mapNotNull { fieldRootInfo[it]?.first }, emptySet(), pos))
        val refs = LinkedHashSet<Int>()
        refs += pid
        refs += roots
        for (r in roots) fieldRootInfo[r]?.let { refs += it.first }   // a collection in a field: the loan also needs the object alive
        return AVal(rt, false, -1, refs, emptySet(), true, false, pos)
    }

    /** The owners an element loan read through [v] depends on: the collections [v] stands for, or the owner of a Strict container. */
    private fun loanRoots(v: AVal): Set<Int>? {
        val c = collRoots(v)
        if (c != null) return c
        if (v.type.tracked && !v.fresh && v.refs.isNotEmpty()) return v.refs
        return null
    }

    private fun killLoansOf(v: AVal) {
        val roots = loanRoots(v) ?: return
        killLoans(roots)
    }

    /** Ends every element loan of the collections [roots]: the elements may have been removed, replaced or released. */
    private fun killLoans(roots: Set<Int>) {
        // a change of the collection of an object that is itself an element loan does not end that loan (`n = n.children[c]; n.children[d] = x` keeps `n`)
        val self = roots.mapNotNull { fieldRootInfo[it]?.first }.filter { it < 0 }.toSet()
        for ((id, sl) in st.slots.entries.toList()) {
            if (id >= 0 || sl.bits and O == 0 || id in self) continue
            var hit = false
            for (r in sl.refs) if (r in roots) hit = true
            if (hit) put(id, Slot(F, sl.refs, sl.sites, sl.vpos))
        }
    }

    private fun checkMovedFields(pos: Pos) {
        for ((k, f) in st.fields.entries.toList()) {
            if (f.bits and FM == 0) continue
            diag(
                "M0113", f.movePos.minOrNull() ?: pos, "field `${name(k.slot)}.${k.field}` is moved out and not restored", "moved out here",
                "assign `${name(k.slot)}.${k.field} = ...` (or `null`) before calls and before the function ends", slot = k.slot,
            )
            st = st.withField(k, FieldSt((f.bits and FM.inv()) or FU, emptySet()))
        }
    }

    private fun evalFree(e: MExpr.Free, env: Env): AVal {
        val unit = AVal.untracked(MType.UNIT, e.pos)
        val allocLocal = e.alloc as? MExpr.Local
        if (e.alloc != null && allocLocal == null) discard(eval(e.alloc, env))
        val t = e.target
        if (t !is MExpr.Local) {
            val v = eval(t, env)
            if (!v.tracked) { if (t.type.tk == TK.Managed) managedFree(e); return unit }
            if (v.fresh) {
                if (v.sites.any { it.kind == SK.Arena } && e.kind == FreeKind.Free) {
                    diag("M0106", e.pos, "an arena object cannot be freed individually", "freed here", "remove the `free()`: the arena releases it")
                }
                return unit
            }
            if (v.refs.isNotEmpty()) {
                diag("M0107", e.pos, "cannot free a borrowed value", "this value is only borrowed", "free the owner instead", slot = v.refs.first())
            }
            return unit
        }
        val id = t.local.id
        if (!t.local.type.tracked) {
            if (t.local.type.tk == TK.Managed) managedFree(e)
            return unit
        }
        val s = slot(id) ?: return unit
        val nm = t.local.name
        if (s.bits and K != 0 && s.bits and B == 0 && s.bits and O != 0 && s.refs.isNotEmpty()) {
            diag(
                "M0107", e.pos, "`$nm` borrows `${name(s.refs.first())}` and cannot be freed", "`$nm` is released when its scope ends",
                "remove the `free()`: the object is released with `$nm`, and it must not outlive `${name(s.refs.first())}`", slot = id,
            )
            return unit
        }
        if (s.bits and B != 0) {
            // an alias (or borrowed parameter) can never release the value; the owner is unaffected
            diag(
                "M0107", e.pos, "`$nm` is borrowed and cannot be freed", "`$nm` does not own its value",
                "free the owner, or declare the parameter `@Owned`", slot = rootsOf(id).firstOrNull() ?: id,
            )
            return unit
        }
        val sites = sitesOf(id)
        if (sites.any { it.kind == SK.Arena }) {
            if (e.kind == FreeKind.Free) {
                diag("M0106", e.pos, "arena object `$nm` cannot be freed individually", "freed here", "remove the `free()`: the arena releases it")
            }
            return unit
        }
        if (s.bits and F != 0) {
            diag("M0103", e.pos, if (s.bits and (O or N) != 0) "`$nm` may be freed twice" else "`$nm` is freed twice", "freed again here", "remove the second `free()`", slot = id)
            put(id, Slot(F, s.refs, s.sites, s.vpos))
            return unit
        }
        if (s.bits and M != 0 && s.bits and (O or N) == 0) {
            diag("M0107", e.pos, "`$nm` is no longer owned (it was moved)", "moved before", "free the new owner instead", slot = id)
            return unit
        }
        if (s.bits and M != 0) {
            diag("M0107", e.pos, "`$nm` may no longer be owned (it was moved on some path)", "moved on another path", "free the new owner instead", slot = id)
            put(id, Slot(F, s.refs, s.sites, s.vpos))
            return unit
        }
        // allocator named explicitly: alloc.free(x)
        if (allocLocal != null) {
            val ak = allocLocal.local.type.alloc
            val aid = rootId(allocLocal.local.id)
            val mism = sites.any { s2 ->
                (s2.kind == SK.Heap || s2.kind == SK.Pool || s2.kind == SK.Debug) &&
                    (siteKind(ak ?: AllocKind.Other) != s2.kind || (s2.kind == SK.Pool && s2.id != aid && s2.id >= 0))
            }
            if (mism) {
                val have = sites.first { it.kind.obligation }.kind.label
                diag("M0108", e.pos, "`$nm` was allocated by `$have`, not `${ak ?: "?"}`", "wrong allocator", "use `$nm.free()` or the allocator that created it", slot = id)
            }
        }
        put(id, Slot(F, s.refs, s.sites, s.vpos))
        st = st.withoutFieldsOf(id)
        return unit
    }

    private fun managedFree(e: MExpr.Free) {
        diag("M0107", e.pos, "cannot free a Managed (refcounted) object", "Managed objects are not owned by Strict code", "remove the `free()`")
    }

    private fun evalMove(e: MExpr.Move, env: Env): AVal {
        val t = e.target
        if (t is MExpr.FieldGet && t.obj is MExpr.Local && t.type.tracked && !e.implicit) {
            val o: MExpr.Local = t.obj
            val ov = eval(o, env)
            val key = FKey(o.local.id, t.field.name)
            val cur = st.fields[key]
            if (cur != null && cur.bits and FM != 0 && cur.bits and (FU or FN or FO) == 0) {
                diag("M0104", e.pos, "use of moved field `${o.local.name}.${t.field.name}`", "moved out before")
            }
            st = st.withField(key, FieldSt(FM, setOf(e.pos)))
            return AVal(t.type, true, -1, emptySet(), setOf(Site(e.pos, SK.Field, -1)), true, false, e.pos).also { discardNone(ov) }
        }
        if (t is MExpr.Local && t.type.tracked) {
            val s = slot(t.local.id)
            if (s == null) return AVal.untracked(t.type, e.pos)
            checkUse(t.local.id, t.pos)
            val s2 = slot(t.local.id)!!
            if (e.implicit && s2.bits and B != 0 && s2.bits and O != 0 && hasDefault(s2.sites) && !u.suspend) {
                // `if (c) Box(1) else other`: an owned object on one path, a borrowed one on the other: nobody can tell at run time who releases it
                diag(
                    "M0109", e.pos, "the value is a new default-allocated object on some paths and a borrowed one on others", "owner and borrower are mixed here",
                    "make every branch produce an owner (`other.move()` or a new object), or bind the borrowed value to a variable first",
                )
            }
            if (s2.bits and B != 0) {
                if (!e.implicit) {
                    diag(
                        "M0109", e.pos, "borrowed `${t.local.name}` cannot be moved", "`${t.local.name}` is only borrowed",
                        "only owners can be moved; make the parameter `@Owned`", slot = rootsOf(t.local.id).firstOrNull() ?: -1,
                    )
                }
                return AVal(t.type, false, -1, rootsOf(t.local.id), sitesOf(t.local.id), s2.bits and N != 0, false, if (e.implicit) s2.vpos else e.pos, caps = s2.bits and K != 0)
            }
            if (reporting) t.moves = true
            put(t.local.id, Slot(M, s2.refs, s2.sites, s2.vpos))
            return AVal(t.type, true, -1, emptySet(), s2.sites, s2.bits and N != 0, s2.bits == N, if (e.implicit) s2.vpos else e.pos, call = slotCalls[t.local.id])
        }
        val v = eval(t, env)
        return v
    }

    private fun discardNone(@Suppress("UNUSED_PARAMETER") v: AVal) {}

    /**
     * The captures of a closure (lambda, anonymous object, local class instance) borrow their owners: the closure object does not
     * count references to a Strict object, so it must not outlive the owner. Returns the owners borrowed and the arena sites seen;
     * where the closure goes decides later (a use after the owner is gone is M0101, an escape is M0112, see [escapeClosure]).
     */
    private fun captureOf(captures: List<MLocal>, pos: Pos, assigned: Set<Int>? = null): Pair<Set<Int>, Set<Site>> {
        val refs = LinkedHashSet<Int>()
        val arena = HashSet<Site>()
        for (c in captures) {
            val s = slot(c.id) ?: continue
            if (s.bits and K != 0) {   // a closure captured by another closure
                checkUse(c.id, pos)
                refs += rootsOf(c.id)
                arena += s.sites
                continue
            }
            if (!c.type.tracked) continue
            checkUse(c.id, pos)
            val s2 = slot(c.id) ?: s
            val sites = sitesOf(c.id)
            if (sites.any { it.kind == SK.Arena }) { arena += sites.filter { it.kind == SK.Arena }; continue }
            if (s2.bits and B != 0) {
                refs += rootsOf(c.id)
            } else if (s2.bits and O != 0) {
                if (c.isVar && (assigned == null || c.id in assigned)) {
                    // a lambda that does not assign the variable only reads the owner the variable holds when it runs (the heap cell of
                    // the variable does not count it): it borrows the variable like a `val`, whatever the variable holds later is checked at the
                    // use. A variable the closure assigns (or a local class that might) would change the owner behind the checker's back.
                    diag(
                        "M0112", pos, "owned `var ${c.name}` is captured by a closure", "the closure assigns the variable (or may: a local class), so the owner it holds changes behind the checker's back",
                        "capture a `val` (`val ${c.name}2 = ${c.name}`), or pass `${c.name}` as a parameter", slot = c.id,
                    )
                    put(c.id, Slot(X, s2.refs, s2.sites, s2.vpos))
                } else {
                    refs += c.id
                }
            }
        }
        return Pair(refs, arena)
    }

    private fun evalLambda(e: MExpr.Lambda, env: Env): AVal {
        // the captured `var`s of tracked type that the lambda (or a lambda inside it) assigns
        val vars = HashSet<Int>()
        for (c in e.captures) if (c.isVar && c.type.tracked) vars += c.id
        val assigned = HashSet<Int>()
        if (vars.isNotEmpty()) MemoryChecker.walk(e.body, enterLambdas = true, onStmt = { if (it is MStmt.Assign && it.local.id in vars) assigned += it.local.id }, onExpr = {})
        val (refs, arena) = captureOf(e.captures, e.pos, assigned)
        queue(e, env.ctx)
        return AVal(MType.OTHER, false, -1, refs, arena, false, false, e.pos, caps = refs.isNotEmpty(), child = e.child)
    }

    /**
     * The block of a `launch` / `async` that its structured scope joins before the call returns that owns the block: it may borrow what the
     * unit around it borrowed from the function that called the scope builder (those owners live until the builder returns), but not an owner
     * or loan that belongs to the block itself (it can be freed before the child runs).
     */
    private fun escapeChild(v: AVal, how: String) {
        val bad = v.refs.filter { it !in u.capturedIds }
        if (bad.isEmpty()) return
        escapeClosure(AVal(v.type, v.fresh, v.src, bad.toSet(), v.sites, v.nullable, v.isNull, v.pos, caps = true), how)
    }

    /**
     * A closure that borrows its owners flows somewhere it can outlive them ([how]): M0112 for owners, M0109 for borrowed values.
     * Returns true when something was reported.
     */
    private fun escapeClosure(v: AVal, how: String): Boolean {
        if (!v.caps || v.refs.isEmpty()) return false
        val borrowed = v.refs.firstOrNull { slot(it)?.let { s -> s.bits and B != 0 } == true }
        if (borrowed != null) {
            val nm = name(borrowed)
            diag(
                "M0109", v.pos, "borrowed `$nm` is captured by a closure that escapes", "the closure borrows `$nm` and $how",
                "pass the value as a parameter instead", slot = borrowed,
            )
            return true
        }
        val owner = v.refs.first()
        val nm = name(owner)
        diag(
            "M0112", v.pos, "owned `$nm` is captured by a closure that escapes", "the closure borrows `$nm` and $how",
            "keep the closure inside the function (call it or pass it to a function that does not keep it), make the called function `inline`, pass `$nm` as a parameter, or read what you need before creating the closure",
            slot = owner,
        )
        for (r in v.refs) slot(r)?.let { s -> if (s.bits and O != 0) put(r, Slot((s.bits and O.inv()) or X, s.refs, s.sites, s.vpos)) }
        return true
    }

    // ---- statements / operations ---------------------------------------------------------------

    /** Applies [op] to [st]; returns the resulting state (also stored in [st]). */
    fun transfer(op: Op): State {
        when (op) {
            is Op.Decl -> doDecl(op)
            is Op.Assign -> doAssign(op)
            is Op.SetField -> doSetField(op)
            is Op.SetGlobal -> doSetGlobal(op)
            is Op.Eval -> discard(eval(op.e, op.env))
            is Op.Cond -> discard(eval(op.e, op.env))
            is Op.Return -> doReturn(op)
            is Op.Exit -> doExit(op)
            is Op.Close -> doClose(op)
            is Op.ThreadStart -> doThreadStart(op)
            is Op.ThreadEnd -> doThreadEnd(op)
            is Op.Throw -> discard(eval(op.value, op.env))
        }
        return st
    }

    private fun doDecl(op: Op.Decl) {
        val s = op.s
        val l = s.local
        checkAnnotLocal(s)
        if (s.init == null) {
            if (l.type.tracked) put(l.id, Slot(0, emptySet(), emptySet(), s.pos))
            return
        }
        var v = eval(s.init, op.env)
        // explicit `@Owned` annotation on the declaration moves the right-hand side
        if (s.annot.owned != null && v.tracked && !v.fresh && v.src >= 0) {
            val src = slot(v.src)
            if (src != null && src.bits and B == 0) {
                markMove(v)
                put(v.src, Slot(M, src.refs, src.sites, src.vpos))
                v = AVal(v.type, true, -1, emptySet(), src.sites, v.nullable, false, v.pos)
            }
        }
        bind(l, v, forceAlias = s.annot.borrowed != null, assign = false, scopes = op.scopes)
    }

    private fun doAssign(op: Op.Assign) {
        val l = op.s.local
        val v = eval(op.s.value, op.env)
        bind(l, v, forceAlias = false, assign = true, scopes = op.scopes)
    }

    /** The call whose fresh result a local was bound to last (a conduit that is moved on keeps the call, so that a result nobody binds is found). */
    private val slotCalls = HashMap<Int, MExpr.Call>()

    private fun bind(l: MLocal, v: AVal, forceAlias: Boolean, assign: Boolean, scopes: Set<Int>) {
        if (v.fresh && v.call != null) slotCalls[l.id] = v.call
        // arena objects assigned to a local that outlives the arena scope
        if (assign && v.sites.isNotEmpty()) {
            arenaEscape(v) { id -> id !in scopes }
        }
        if (!l.type.tracked) {
            discard(v)
            if (l.type.holds && isEntryType(l.type) && v.refs.isEmpty()) {
                // an entry of a map the checker has no root for (`n.children.entries.first()`): a view, no collection of its own, so no loans hang on this variable
                if (slot(l.id) != null) put(l.id, Slot(0, emptySet(), emptySet(), v.pos))
                return
            }
            if (l.type.holds) {
                // a collection of tracked values: assigning it ends the loans of the elements of the old value
                if (assign) killLoans(setOf(l.id))
                put(l.id, Slot(C or (if (v.refs.isNotEmpty()) B else 0), v.refs, emptySet(), v.pos))
                return
            }
            if (v.caps && v.refs.isNotEmpty()) put(l.id, Slot(B or K, v.refs, v.sites, if (v.pos.line > 0) v.pos else l.pos))
            else if (slot(l.id) != null) put(l.id, Slot(0, emptySet(), emptySet(), v.pos))
            return
        }
        val old = slot(l.id)
        if (assign && old != null && old.bits and O != 0) {
            for (s in old.sites) if (s.kind.obligation && s.kind != SK.Param || s.kind == SK.Param) {
                if (!s.kind.obligation) continue
                diag(
                    "M0102", s.pos, "allocation from `${s.kind.label}` is never released", "allocated here; path: overwritten",
                    "call `${l.name}.free()` before assigning a new value", slot = l.id,
                )
            }
        }
        st = st.withoutFieldsOf(l.id)
        val kb = if (v.caps) K else 0
        val vp = if (v.caps && v.pos.line == 0) l.pos else v.pos
        val ns: Slot = when {
            v.isNull -> Slot(N, emptySet(), emptySet(), v.pos)
            forceAlias -> Slot(B or kb or (if (v.nullable) N else 0), v.refs.ifEmpty { if (v.src >= 0) setOf(v.src) else emptySet() }, emptySet(), v.pos)
            v.fresh && v.caps && v.refs.isNotEmpty() -> Slot(O or K or (if (v.nullable) N else 0), v.refs, v.sites, v.pos)
            v.fresh -> Slot(O or (if (v.nullable) N else 0), emptySet(), v.sites, v.pos)
            v.tracked && (v.src >= 0 || v.refs.isNotEmpty()) ->
                Slot(B or kb or (if (v.nullable) N else 0), v.refs.ifEmpty { setOf(v.src) }, if (v.caps) v.sites else emptySet(), vp)
            else -> Slot(B or (if (v.nullable) N else 0), emptySet(), emptySet(), v.pos)
        }
        put(l.id, ns)
        if (ns.bits and B != 0 && reporting) l.aliasSeen = true
        // (a coroutine body keeps the old behaviour: a default-allocated object lives and dies with its local, it cannot be handed on)
        if (ns.bits and O != 0 && hasDefault(ns.sites) && !u.suspend) {
            if (l.dropCapable) {
                markDropOwner(l)
                markOwned(ns.sites)
            } else if (l.origin != null && v.fresh && v.call != null && reporting) {
                // a parameter of an inline lambda (`make().let { it.v }`) only borrows the value: the call result is released at the end of the enclosing scope
                v.call.ownedTemp = true
            }
        }
        v.initFields?.let { m -> for ((f, b) in m) st = st.withField(FKey(l.id, f), FieldSt(b, emptySet())) }
    }

    private fun fieldState(o: MExpr?, f: MField): FieldSt? = (o as? MExpr.Local)?.let { st.fields[FKey(it.local.id, f.name)] }

    private fun doSetField(op: Op.SetField) {
        val s = op.s
        val ov = eval(s.obj, op.env)
        discard(ov)
        val vv = eval(s.value, op.env)
        val f = s.field
        if (f.type.holds && !f.type.tracked) for (o in ownerIds(ov)) fieldRootIds[o to f.name]?.let { killLoans(setOf(it)) }
        if (!vv.tracked) escapeClosure(vv, "is stored into field `${f.name}` of `${f.owner.name.substringAfterLast('.')}`")
        // Strict value stored into a Managed class
        if (f.owner.mode == MemMode.Managed) {
            if (vv.tracked) {
                diag(
                    "M0110", vv.pos, "Strict value stored in a field of Managed code", "stored in `${f.owner.name.substringAfterLast('.')}.${f.name}`",
                    "keep the value in Strict code, or use a Strict wrapper class",
                )
            }
            return
        }
        val ownerSites = ov.sites
        if (!f.type.tracked || f.borrowed) {
            if (!f.borrowed) checkOwnedInto(ownerSites, vv, f)
            else if (vv.sites.isNotEmpty() && vv.sites.any { it.kind == SK.Arena }) {
                // a non-owning reference does not make the arena object escape
            }
            discard(vv)
            return
        }
        // owning, tracked field
        if (vv.tracked && !vv.fresh && !vv.isNull && ((vv.src < 0 && vv.refs.isNotEmpty()) || (vv.src >= 0 && slot(vv.src)?.let { it.bits and B != 0 } == true))) {
            // a loan of an arena value (a pointer into a memScoped scope) stored in an object that outlives the arena: M0105, not a follow-up M0109
            if (vv.sites.any { it.kind == SK.Arena } && !checkOwnedInto(ownerSites, vv, f)) return
            if (!takeOwnership(vv, "stored in an owned field") && vv.src >= 0) {
                val idx = u.paramIndex[vv.src]
                if (idx != null) u.fn?.storesParams?.add(idx)
            }
            return
        }
        val fs = fieldState(s.obj, f)
        val objIsKnownRoot = s.obj is MExpr.Local || s.obj is MExpr.FieldGet
        if (objIsKnownRoot && (fs == null || fs.bits and (FU or FO) != 0) && ownerSites.none { it.kind == SK.Arena }) {
            diag(
                "M0102", s.pos, "assigning to owned field `${f.name}` loses its previous value", "old value overwritten here; path: overwritten",
                "call `.free()` on the old value (or move it out with `.move()`) first",
            )
        }
        if (vv.tracked) {
            if (!checkOwnedInto(ownerSites, vv, f)) {
                // already reported (M0115/M0105); the value is considered stored
            } else if (vv.sites.any { it.kind == SK.Arena } && ownerSites.none { it.kind == SK.Arena && vv.sites.any { s2 -> s2.id == it.id } }) {
                // handled by checkOwnedInto for non-arena targets
            }
            if (vv.sites.any { it.kind == SK.Arena } && ownerSites.any { it.kind == SK.Arena }) {
                // arena value into arena object of the same arena: fine, no move obligations
                if (vv.src >= 0) slot(vv.src)?.let { sl -> if (sl.bits and B == 0) put(vv.src, Slot(M, sl.refs, sl.sites, sl.vpos)) }
            } else {
                takeOwnership(vv, "stored in an owned field")
            }
        }
        val o = s.obj
        if (o is MExpr.Local) {
            st = st.withField(FKey(o.local.id, f.name), FieldSt(if (vv.isNull) FN else (FO or if (vv.nullable) FN else 0), emptySet()))
        }
    }

    private fun doSetGlobal(op: Op.SetGlobal) {
        val s = op.s
        val v = eval(s.value, op.env)
        if (!v.tracked) escapeClosure(v, "is stored into a global")
        if (s.managedOwner && v.tracked) {
            diag("M0110", v.pos, "Strict value stored in a global of Managed code", "stored here", "keep the value in Strict code")
            return
        }
        if (v.sites.isNotEmpty()) arenaEscape(v) { true }
        if (v.tracked) takeOwnership(v, "stored in a global")
    }

    private fun doReturn(op: Op.Return) {
        val e = op.value
        if (e != null) {
            val v0 = eval(e, op.env)
            // an anonymous object has no position of its own: report at the `return`
            val at = if (op.pos.line > 0) op.pos else u.fn?.pos ?: op.pos
            val v = if (v0.pos.line > 0) v0 else AVal(v0.type, v0.fresh, v0.src, v0.refs, v0.sites, v0.nullable, v0.isNull, at, v0.strLit, v0.initFields, v0.caps, v0.child)
            if (v.sites.isNotEmpty()) arenaEscape(v) { true }
            // a closure may be returned while it only borrows parameters (the caller learns that from the summary, see FnSummary.carries)
            // ... unless the declared result is a Strict type: the caller owns such a value (and frees it), a closure object that only borrows
            // its receiver or a parameter cannot be handed over as that owner (`fun reader(): Reader = object : Reader { ... n ... }`)
            if (!v.tracked && v.caps && v.refs.isNotEmpty() && (u.retTracked && !u.isLambda || v.refs.any { r -> slot(r)?.let { it.bits and B == 0 } != false })) escapeClosure(v, "is returned" + if (u.retTracked && !u.isLambda) " as owned" else "")
            if (v.tracked) {
                if (v.caps && v.refs.isNotEmpty() && !u.isLambda && !u.retOwned) {
                    // an instance of an anonymous / local class that borrows its receiver or a parameter, returned as a value of a type that owns nothing (`Any`)
                    // (the object stays a counted temporary of the allocation: the reference counted `Any` result carries its count)
                    if (!borrowsOnlyParams(v)) escapeClosure(v, "is returned as owned")
                } else if (u.retOwned && !u.isLambda) {
                    takeOwnership(v, "returned as owned")
                } else if (v.fresh && !u.isLambda) {
                    // borrowed return of a fresh value: nobody owns it
                    discard(v)
                } else if (u.isLambda) {
                    // the result of a lambda is a reference counted (+1) reference for whoever calls it: the allocation itself may be returned (it is
                    // not released here), an owner of the default allocator is released when the lambda ends and cannot be handed out
                    if (hasDefault(v.sites) && !v.fresh && v.src >= 0 && slot(v.src)?.let { it.bits and B == 0 } == true) {
                        for (s in v.sites) if (s.kind == SK.Default) {
                            diag(
                                "M0204", s.pos, "no allocator for an escaping Strict value", "the owner `${name(v.src)}` is released when the lambda ends but is returned from it",
                                "return the allocation itself (`{ Box(1) }`), or build the value in a function that returns it",
                                slot = v.src,
                            )
                        }
                    } else if (v.fresh && !hasDefault(v.sites)) discard(v)
                }
            }
        }
        checkMovedFields(op.pos)
    }

    private fun doExit(op: Op.Exit) {
        for (l in op.locals.asReversed()) {
            if (l.type.holds) {
                // the collection ends with its scope: the loans of its elements end with it
                val cs = slot(l.id)
                // a collection that another collection variable stands for (`outer = inner`) lives on through it: the object is not released
                val aliased = st.slots.any { (id, sl) -> id >= 0 && id != l.id && sl.bits and C != 0 && l.id in sl.refs }
                if (cs != null && cs.bits and C != 0 && cs.bits and B == 0 && !aliased) {
                    killLoans(setOf(l.id))
                    put(l.id, Slot(F, cs.refs, cs.sites, cs.vpos))
                }
                continue
            }
            if (!l.type.tracked) continue
            val s = slot(l.id) ?: continue
            if (s.bits and O == 0) continue
            // the end of the scope releases an object of the default allocator: an alias that outlives the owner is a use after free (7.1)
            if (l.dropCapable && !u.suspend && hasDefault(s.sites) && s.bits and B == 0 && s.sites.none { it.kind.obligation }) {
                put(l.id, Slot((s.bits and O.inv()) or F, s.refs, s.sites, s.vpos))
                continue
            }
            for (site in s.sites) {
                if (!site.kind.obligation) continue
                val where = site.pos
                diag(
                    "M0102", where, "allocation from `${site.kind.label}` is never released", "allocated here; path: ${op.path}",
                    helpFor(l.name, site.kind), slot = l.id,
                )
            }
            put(l.id, Slot((s.bits and O.inv()) or X, s.refs, s.sites, s.vpos))
        }
        if (op.isFunction) checkMovedFields(Pos.NONE)
    }

    private fun helpFor(n: String, k: SK): String = when (k) {
        SK.Resource -> "call `$n.close()` or wrap in `$n.use { ... }`"
        SK.Param -> "release `$n` with `$n.free()` or pass it on to an `@Owned` parameter"
        SK.Extern -> "call the C function named in `@Owned(free = ...)` with `$n` on every path (or `nativeHeap.free($n)` for memory from `malloc`)"
        else -> "call `$n.free()` on every path (or `$n.use { ... }` for AutoCloseable), or move it to an owner"
    }

    private fun doClose(op: Op.Close) {
        val l = op.target
        if (!l.type.tracked) return
        val s = slot(l.id) ?: return
        val nm = l.name
        if (s.bits and B != 0) {
            diag(
                "M0107", op.pos, "`$nm` is borrowed and cannot be closed by `use`", "`$nm` does not own its value",
                "call `use` on the owner", slot = rootsOf(l.id).firstOrNull() ?: l.id,
            )
            return
        }
        if (s.bits and F != 0) {
            diag("M0103", op.pos, "`$nm` is closed twice", "closed again at the end of this block", "remove the extra `close()`/`free()`", slot = l.id)
        }
        put(l.id, Slot(F, s.refs, s.sites, s.vpos))
    }

    private fun doThreadStart(op: Op.ThreadStart) {
        for (c in op.captures) {
            if (!c.type.tracked) {
                val ks = slot(c.id)
                if (ks != null && ks.bits and K != 0) escapeClosure(AVal(c.type, false, c.id, rootsOf(c.id), emptySet(), false, false, ks.vpos, caps = true), "is used by a thread that may outlive it")
                continue
            }
            val s = slot(c.id) ?: continue
            if (c.type.isResource) {
                diag("M0206", op.pos, "`${c.type.alloc}` is not ThreadSafe and cannot cross a thread boundary", "captured here", "use a `Heap` allocator for values shared with threads")
                continue
            }
            val sites = sitesOf(c.id)
            when {
                hasDefault(sites) && s.bits and B == 0 ->
                    diag(
                        "M0204", op.pos, "no allocator for an escaping Strict value", "the default allocator's object `${c.name}` cannot be moved into a thread",
                        "allocate it with a `Heap` (`context(h: Heap)` or `@AllocatedBy(Heap::class)`)", slot = c.id,
                    )
                sites.any { it.kind == SK.Arena } ->
                    diag("M0105", op.pos, "arena object `${c.name}` cannot be moved into a thread", "captured here", "copy the data out of the arena first")
                sites.any { it.kind == SK.Pool } ->
                    diag("M0206", op.pos, "`Pool` is not ThreadSafe: `${c.name}` cannot cross a thread boundary", "captured here", "use a `Heap` allocator")
                s.bits and B != 0 ->
                    diag("M0109", op.pos, "borrowed `${c.name}` cannot be moved into a thread", "captured here", "pass an owned value", slot = rootsOf(c.id).firstOrNull() ?: -1)
                else -> checkUse(c.id, op.pos)
            }
        }
    }

    private fun doThreadEnd(op: Op.ThreadEnd) {
        for (c in op.captures) {
            if (!c.type.tracked) continue
            val s = slot(c.id) ?: continue
            if (s.bits and B != 0) continue
            if (s.bits and O != 0) {
                for (site in s.sites) if (site.kind.obligation) {
                    diag(
                        "M0102", site.pos, "allocation from `${site.kind.label}` is never released", "allocated here; path: thread lambda ends without releasing `${c.name}`",
                        "free `${c.name}` inside the thread lambda", slot = c.id,
                    )
                }
            }
            put(c.id, Slot(M, s.refs, s.sites, s.vpos))
        }
    }

    // ---- annotation checks (M0111) ---------------------------------------------------------------

    private fun checkAnnotLocal(s: MStmt.Decl) = annotCheck(s.annot, s.local.type)

    fun annotCheck(a: MAnnot, t: MType) {
        if (a.owned != null && !t.tracked && !t.typeParam) {
            diag(
                "M0111", a.owned, "`@Owned` on `${t}`, which has no ownership", "no ownership here",
                "remove the annotation",
            )
        }
        if (a.owned != null && a.borrowed != null) {
            diag("M0111", maxOf(a.owned, a.borrowed), "`@Owned` and `@Borrowed` on the same declaration", "contradicts the other annotation", "keep only one of them")
        }
    }

    // ---- exit of function ----------------------------------------------------------------------

    fun initParam(p: MParam) {
        val l = p.local
        val t = l.type
        if (!t.tracked) {
            if (t.holds && !p.isContext) put(l.id, Slot(C or B, emptySet(), emptySet(), l.pos))
            return
        }
        if (p.isContext) {
            if (t.isResource) put(l.id, Slot(B, emptySet(), emptySet(), l.pos))
            return
        }
        if (p.owned) {
            put(l.id, Slot(O or (if (t.nullable) N else 0), emptySet(), setOf(Site(l.pos, SK.Param, l.id)), l.pos))
        } else {
            put(l.id, Slot(B or (if (t.nullable) N else 0), emptySet(), emptySet(), l.pos))
        }
    }
}
