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

    private fun name(id: Int) = u.names[id] ?: "value"

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
        return if (s.bits and B != 0 && s.refs.isNotEmpty()) s.refs else setOf(id)
    }

    /** Use of slot [id] (read, call, borrow): M0101 / M0104 when the owner is gone. */
    private fun checkUse(id: Int, pos: Pos) {
        for (r in rootsOf(id)) {
            val s = slot(r) ?: continue
            if (s.bits and (F or M) == 0) continue
            val maybe = s.bits and (F or M).inv() and (O or N or B or X) != 0
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

    private fun discard(v: AVal) {
        if (!v.fresh) return
        for (s in v.sites) if (s.kind.obligation && s.kind != SK.Unknown) {
            diag(
                "M0102", s.pos, "allocation from `${s.kind.label}` is never released", "allocated here; path: result discarded",
                "bind the result and call `.free()` on it, or pass it to an `@Owned` parameter",
            )
        }
    }

    private fun escape(v: AVal) {
        for (s in v.sites) {
            if (s.kind == SK.Stack) {
                diag(
                    "M0204", s.pos, "no allocator for an escaping Strict value", "this value escapes but no allocator is selectable",
                    "add `context(h: Heap)` to the function or `@AllocatedBy(Heap::class)` to the class",
                )
            } else if (u.noAlloc && s.kind != SK.Param && s.kind != SK.Field && s.kind != SK.Unknown) {
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
        if (!t.tracked || s == null) return AVal.untracked(t, e.pos)
        checkUse(id, e.pos)
        val s2 = slot(id)!!
        val refs = rootsOf(id)
        val sites = sitesOf(id)
        return AVal(t, false, id, refs, sites, s2.bits and N != 0 || t.nullable && s2.bits and (O or B) == 0, false, e.pos)
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
        if (!e.type.tracked) return AVal.untracked(e.type, e.pos)
        val roots = if (ov.refs.isNotEmpty()) ov.refs else if (ov.src >= 0) setOf(ov.src) else emptySet()
        return AVal(e.type, false, -1, roots, ov.sites, true, false, e.pos)
    }

    private fun evalNew(e: MExpr.New, env: Env): AVal {
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
            is AR.NoneFound -> site = Site(e.pos, SK.Stack, -1)
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
                TK.Strict, TK.Alloc -> v.sites.any { it.kind != SK.Arena && it.kind != SK.Stack && it.kind != SK.Param && it.kind != SK.Field }
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
            if (p == null || p.isContext) { if (!v.tracked) arenaEscapeArg(v); continue }
            if (!v.tracked) { arenaEscapeArg(v); continue }
            if (foreign) {
                if (p.annot.borrowed == null && !fn.borrowsAll) {
                    diag(
                        "M0110", v.pos, "Strict value handed to Managed code", "passed to a Managed function that does not promise to borrow",
                        "mark the parameter `@Borrowed`, or wrap the call in a Strict function",
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
        if (rv != null) discard(rv)
        // exception: methods of local owners may be called while a field is moved out
        if (!(e.receiver is MExpr.Local)) checkMovedFields(e.pos)
        val args = processArgs(fn, rv, e.args, env, e)
        val rt = e.type
        if (!rt.tracked) return AVal.untracked(rt, e.pos)
        if (fn.mode == MemMode.Managed || fn.external) return AVal.untracked(rt, e.pos)
        if (fn.ret.annot.borrowed != null) {
            val refs = HashSet<Int>()
            val sites = HashSet<Site>()
            rv?.let { refs += it.refs; sites += it.sites }
            for ((i, a) in args.withIndex()) {
                val p = fn.params.getOrNull(i)
                if (p != null && !p.isContext && a.tracked && !a.fresh) { refs += a.refs; sites += a.sites }
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
        val sk = kind?.let { siteKind(it) } ?: SK.Unknown
        return AVal(rt, true, -1, emptySet(), setOf(Site(e.pos, sk, id)), rt.nullable, false, e.pos)
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
            val o = t.obj as MExpr.Local
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
            if (s2.bits and B != 0) {
                if (!e.implicit) {
                    diag(
                        "M0109", e.pos, "borrowed `${t.local.name}` cannot be moved", "`${t.local.name}` is only borrowed",
                        "only owners can be moved; make the parameter `@Owned`", slot = rootsOf(t.local.id).firstOrNull() ?: -1,
                    )
                }
                return AVal(t.type, false, -1, rootsOf(t.local.id), sitesOf(t.local.id), s2.bits and N != 0, false, if (e.implicit) s2.vpos else e.pos)
            }
            put(t.local.id, Slot(M, s2.refs, s2.sites, s2.vpos))
            return AVal(t.type, true, -1, emptySet(), s2.sites, s2.bits and N != 0, s2.bits == N, if (e.implicit) s2.vpos else e.pos)
        }
        val v = eval(t, env)
        return v
    }

    private fun discardNone(@Suppress("UNUSED_PARAMETER") v: AVal) {}

    private fun evalLambda(e: MExpr.Lambda, env: Env): AVal {
        val arenaSites = HashSet<Site>()
        for (c in e.captures) {
            if (!c.type.tracked) continue
            val s = slot(c.id) ?: continue
            checkUse(c.id, e.pos)
            val s2 = slot(c.id) ?: s
            val sites = sitesOf(c.id)
            if (sites.any { it.kind == SK.Arena }) { arenaSites += sites.filter { it.kind == SK.Arena }; continue }
            if (s2.bits and B != 0) {
                diag(
                    "M0109", e.pos, "borrowed `${c.name}` is captured by a lambda that escapes", "captures a borrowed value",
                    "pass the value as a parameter instead", slot = rootsOf(c.id).firstOrNull() ?: -1,
                )
            } else if (s2.bits and O != 0) {
                diag(
                    "M0112", e.pos, "owned `${c.name}` is captured by a lambda that escapes", "captured here",
                    "pass the value as a parameter, or read what you need before creating the lambda", slot = c.id,
                )
                put(c.id, Slot(X, s2.refs, s2.sites, s2.vpos))
            }
        }
        queue(e, env.ctx)
        return AVal(MType.OTHER, false, -1, emptySet(), arenaSites, false, false, e.pos)
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

    private fun bind(l: MLocal, v: AVal, forceAlias: Boolean, assign: Boolean, scopes: Set<Int>) {
        // arena objects assigned to a local that outlives the arena scope
        if (assign && v.sites.isNotEmpty()) {
            arenaEscape(v) { id -> id !in scopes }
        }
        if (!l.type.tracked) { discard(v); return }
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
        val ns: Slot = when {
            v.isNull -> Slot(N, emptySet(), emptySet(), v.pos)
            forceAlias -> Slot(B or (if (v.nullable) N else 0), v.refs.ifEmpty { if (v.src >= 0) setOf(v.src) else emptySet() }, emptySet(), v.pos)
            v.fresh -> Slot(O or (if (v.nullable) N else 0), emptySet(), v.sites, v.pos)
            v.tracked && (v.src >= 0 || v.refs.isNotEmpty()) ->
                Slot(B or (if (v.nullable) N else 0), v.refs.ifEmpty { setOf(v.src) }, emptySet(), v.pos)
            else -> Slot(B or (if (v.nullable) N else 0), emptySet(), emptySet(), v.pos)
        }
        put(l.id, ns)
        v.initFields?.let { m -> for ((f, b) in m) st = st.withField(FKey(l.id, f), FieldSt(b, emptySet())) }
    }

    private fun fieldState(o: MExpr?, f: MField): FieldSt? = (o as? MExpr.Local)?.let { st.fields[FKey(it.local.id, f.name)] }

    private fun doSetField(op: Op.SetField) {
        val s = op.s
        val ov = eval(s.obj, op.env)
        discard(ov)
        val vv = eval(s.value, op.env)
        val f = s.field
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
            val v = eval(e, op.env)
            if (v.sites.isNotEmpty()) arenaEscape(v) { true }
            if (v.tracked) {
                if (u.retOwned && !u.isLambda) {
                    takeOwnership(v, "returned as owned")
                } else if (v.fresh && !u.isLambda) {
                    // borrowed return of a fresh value: nobody owns it
                    discard(v)
                } else if (u.isLambda) {
                    if (v.fresh) escape(v)
                }
            }
        }
        checkMovedFields(op.pos)
    }

    private fun doExit(op: Op.Exit) {
        for (l in op.locals.asReversed()) {
            if (!l.type.tracked) continue
            val s = slot(l.id) ?: continue
            if (s.bits and O == 0) continue
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
                continue
            }
            val s = slot(c.id) ?: continue
            if (c.type.isResource) {
                diag("M0206", op.pos, "`${c.type.alloc}` is not ThreadSafe and cannot cross a thread boundary", "captured here", "use a `Heap` allocator for values shared with threads")
                continue
            }
            val sites = sitesOf(c.id)
            when {
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
        if (a.owned != null && !t.tracked) {
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
        if (!t.tracked) return
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
