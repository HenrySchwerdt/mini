package mini.backend

/*
 * NullPointerException for the dereference of a polluted non-null value.
 *
 * An erased value (an element of a generic container or of a reified array, a generic result, a generic field) is retyped to the non-null
 * type it has at the use without a test for null ([MirExpr.CastMode.Erased]), like the `checkcast` of the JVM: `arrayOfNulls<String>(n) as
 * Array<String>` or `listOf<String?>(null) as List<String>` is legal, and the value may flow around (printed, compared, stored in a
 * nullable) as the `null` it is. The JVM throws where it is *dereferenced*; so does this pass: a value that may come from such a retyping
 * ("tainted": the retyping itself, a local that is assigned one, a conditional of them) gets a [MirExpr.NotNull] where it is
 *   - the receiver of a field read or write, a virtual or interface call, `String.length`, or an operand of a runtime function that reads it;
 *   - passed to a parameter of a non-null type (the JVM checks the parameters of a public function the same way), stored in a field of a
 *     non-null type, returned from a function of a non-null return type, or thrown.
 * Uses that accept null stay unchecked (`==`, string templates and concatenation, `println`, a nullable or `Any?` parameter, `is`, a
 * store into an array: the elements of an array are checked where they are read). So a tainted value never reaches a place that assumes it
 * is not null, and the values nobody can see being null cost nothing: only the expressions the lowering marked are examined.
 *
 * The cost is kept to one test per value:
 *   - a check makes the *value* (not the local: copies of a local share it) known to be non-null for the rest of the straight-line code
 *     (a branch or loop that is left forgets what was checked inside it, a local that is assigned has a new value);
 *   - a local that is only ever dereferenced (never printed, compared or passed on as a nullable) is checked where it is defined when the code
 *     between the definition and its first dereference does nothing observable (no call, store, throw, `try`): an NPE at the definition
 *     instead of at the use cannot be told apart then, and the loop that reads it does not test it again.
 *
 * The pass runs first on every module (also with `-Xno-mir-opt`), then every Erased cast becomes an Unchecked one.
 */
object NullGuard {
    fun run(module: MirModule): MirModule {
        var fns: HashMap<String, MirFunction>? = null
        var clean: Set<MirLocal> = emptySet()
        var out: ArrayList<MirFunction>? = null
        for (i in module.functions.indices) {
            val f = module.functions[i]
            val scan = ErasedScan()
            scan.walk(f.body)
            if (!scan.found) {
                out?.add(f)
                continue
            }
            if (fns == null) {
                fns = HashMap()
                for (g in module.functions) fns[g.cName] = g
                clean = cleanArrays(module, fns)
            }
            if (out == null) {
                out = ArrayList(module.functions.size)
                for (k in 0 until i) out.add(module.functions[k])
            }
            out.add(GuardPass(f, fns, clean).run())
        }
        if (out == null) return module
        return MirModule(module.sourceName, out, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }

    private class ErasedScan : MirVisitor() {
        var found = false
        override fun visit(e: MirExpr) {
            if (e is MirExpr.Cast && e.mode == MirExpr.CastMode.Erased) found = true
        }
    }

    /** Counts the occurrences of every local. */
    private class LocalCount : MirVisitor() {
        val count = HashMap<MirLocal, Int>()
        override fun visit(e: MirExpr) {
            if (e is MirExpr.Local) count[e.local] = (count[e.local] ?: 0) + 1
        }
    }

    /** Runtime functions that accept a null argument (it reads as "null" or as an absent value). */
    private val NULL_TOLERANT = hashSetOf(
        "mk_to_string", "mk_equals", "mk_string_equals", "mk_print_string", "mk_println_string",
        "mk_array_set_obj", "mk_array_store_obj", "mk_ref_array_set_o", "mk_ref_array_set",
    )

    private fun tolerant(name: String): Boolean = name in NULL_TOLERANT || name.startsWith("mk_string_concat")

    /** Runtime functions without an effect that could be told from a later NullPointerException (they may fail with an index error only). */
    private fun pureRuntime(name: String): Boolean =
        name.startsWith("mk_array_get_") || name == "mk_array_length" || name.startsWith("mk_ref_array_get") || name == "mk_ref_array_length" || name == "mk_ref_array_prefetch"

    fun isNonNullRef(t: MirType): Boolean = t === TString || (t is TObject && !t.nullable)

    private fun trackedArray(t: MirType): Boolean = t is TObject && t.cName == ARRAY_CNAME && t.elem != null && !arrayElemNullable(t.elem!!)

    private fun nullableTyped(t: MirType): Boolean = t === TNString || t === TNull || t is TBoxed || (t is TObject && t.nullable)

    private fun stripCasts(e: MirExpr): MirExpr = if (e is MirExpr.Cast && e.mode == MirExpr.CastMode.Unchecked) stripCasts(e.operand) else e

    private class AddrScan : MirVisitor() {
        val taken = HashSet<String>()
        override fun visit(e: MirExpr) {
            if (e is MirExpr.FuncAddr) taken.add(e.cName)
        }
    }

    private class Site(val callee: MirFunction, val index: Int, val arg: MirExpr)

    private class SiteScan(val fns: Map<String, MirFunction>) : MirVisitor() {
        val sites = ArrayList<Site>()
        override fun visit(e: MirExpr) {
            if (e is MirExpr.Call) {
                val c = fns[e.cName] ?: return
                for (i in e.args.indices) if (i < c.params.size && trackedArray(c.params[i].type)) sites.add(Site(c, i, e.args[i]))
            } else if (e is MirExpr.New) {
                val c = fns[e.ctorCName] ?: return
                for (i in e.args.indices) if (i + 1 < c.params.size && trackedArray(c.params[i + 1].type)) sites.add(Site(c, i + 1, e.args[i]))
            }
        }
    }

    private class AssignScan(val into: HashMap<MirLocal, ArrayList<MirExpr>>) : MirTransform() {
        fun note(l: MirLocal, v: MirExpr?) {
            if (!trackedArray(l.type)) return
            var a = into[l]
            if (a == null) { a = ArrayList(); into[l] = a }
            if (v != null) a.add(v)
        }

        override fun stmt(s: MirStmt): MirStmt {
            if (s is MirStmt.Declare) note(s.local, s.init)
            if (s is MirStmt.Assign) note(s.local, s.value)
            return rebuildStmt(s)
        }
    }

    /**
     * The locals and parameters of an array type that knows a non-null element type and can only hold an array that was created non-null here
     * (`mk_array_new_of` without null elements, copies of such a local, a parameter of a function that is only called directly, with such
     * arrays): reading an element of such an array needs no null check. The arrays that may hold a null have come through a cast (or from
     * somewhere this does not follow: a field, a call result). A null cannot be stored into such an array later either: a store of a value
     * that may be null through a view that allows it is checked against the element type the array was created with (`mk_array_store_obj`).
     */
    private fun cleanArrays(module: MirModule, fns: Map<String, MirFunction>): Set<MirLocal> {
        val taken = HashSet<String>()
        val addr = AddrScan()
        for (f in module.functions) addr.walk(f.body)
        taken.addAll(addr.taken)
        for (c in module.classes) {
            for (v in c.vtable) if (v != null) taken.add(v)
            for (t in c.itables) for (v in t.entries) if (v != null) taken.add(v)
            val oc = c.objectCtor
            if (oc != null) taken.add(oc)
        }
        for (w in module.wasmExports) taken.add(w.cName)
        taken.add(Mangling.MAIN)
        val assigns = HashMap<MirLocal, ArrayList<MirExpr>>()
        val sites = ArrayList<Site>()
        val dirty = HashSet<MirLocal>()
        val aScan = AssignScan(assigns)
        for (f in module.functions) {
            aScan.stmts(f.body)
            val s = SiteScan(fns)
            s.walk(f.body)
            sites.addAll(s.sites)
            if (f.cName in taken) for (p in f.params) if (trackedArray(p.type)) dirty.add(p)
        }
        var changed = true
        while (changed) {
            changed = false
            for (e in assigns.entries) {
                val l = e.key
                if (l in dirty) continue
                for (v in e.value) {
                    if (!cleanExpr(v, dirty)) { dirty.add(l); changed = true; break }
                }
            }
            for (s in sites) {
                val p = s.callee.params[s.index]
                if (p !in dirty && !cleanExpr(s.arg, dirty)) { dirty.add(p); changed = true }
            }
        }
        val clean = HashSet<MirLocal>()
        for (l in assigns.keys) if (l !in dirty) clean.add(l)
        for (f in module.functions) for (p in f.params) if (trackedArray(p.type) && p !in dirty) clean.add(p)
        return clean
    }

    private fun cleanExpr(e: MirExpr, dirty: Set<MirLocal>): Boolean = when (e) {
        is MirExpr.RuntimeCall -> e.cName == "mk_array_new_of" && e.args.size == 3 && e.args[2].let { it is MirExpr.IntConst && it.value == 0 }
        is MirExpr.Local -> trackedArray(e.local.type) && e.local !in dirty
        is MirExpr.Cast -> e.mode == MirExpr.CastMode.Unchecked && cleanExpr(e.operand, dirty)
        else -> false
    }

    private const val NONE = 0
    private const val FOUND = 1
    private const val IMPURE = 2

    private class GuardPass(val f: MirFunction, val fns: Map<String, MirFunction>, val cleanArrays: Set<MirLocal>) : MirTransform() {
        /** Locals of a non-null type that are assigned a tainted value somewhere (flow-insensitive). */
        val taintedLocals = HashSet<MirLocal>()

        // The state while the statements are walked in order: the value number of each tainted local (locals that were assigned one
        // another share it) and the value numbers known to be non-null.
        var vn = HashMap<MirLocal, Int>()
        var cleanVn = HashSet<Int>()
        var nextVn = 0

        /** Checks made by the statement being processed: they hold for the next statements, not for the rest of this one (C leaves the order of operands open). */
        val pending = ArrayList<Int>()
        /** Greater than 0 inside the branches of a conditional expression: a check there does not hold afterwards. */
        var conditional = 0

        // first walk: how the tainted locals are used
        var counting = true
        val derefUses = HashMap<MirLocal, Int>()
        val copyUses = HashMap<MirLocal, Int>()
        val copyTo = HashMap<MirLocal, ArrayList<MirLocal>>()
        var total = HashMap<MirLocal, Int>()
        /** Tainted locals whose every use is a dereference or a copy into such a local. */
        val onlyDeref = HashSet<MirLocal>()

        /** The statement list being walked and the index of the statement, for the look-ahead of an eager check. */
        var curList: List<MirStmt> = emptyList()
        var curIdx = 0

        /** The initializer of a local that is declared once and never assigned. */
        val singleInit = HashMap<MirLocal, MirExpr>()

        fun run(): MirFunction {
            val assigns = ArrayList<Pair<MirLocal, MirExpr>>()
            collect(f.body, assigns)
            val assigned = HashSet<MirLocal>()
            assignedIn(f.body, assigned)
            for (a in assigns) if (a.first !in assigned) singleInit[a.first] = a.second
            var changed = true
            while (changed) {
                changed = false
                for (a in assigns) {
                    val l = a.first
                    if (isNonNullRef(l.type) && l !in taintedLocals && taint(a.second, false)) { taintedLocals.add(l); changed = true }
                }
            }
            val lc = LocalCount()
            lc.walk(f.body)
            total = lc.count
            stmts(f.body) // counts the uses
            counting = false
            for (l in taintedLocals) if (onlyDerefLocal(l, HashSet())) onlyDeref.add(l)
            vn = HashMap()
            cleanVn = HashSet()
            pending.clear()
            val guarded = stmts(f.body)
            val done = Finish().stmts(guarded)
            return if (done === f.body) f else f.withBody(done)
        }

        fun onlyDerefLocal(l: MirLocal, seen: HashSet<MirLocal>): Boolean {
            if (!seen.add(l)) return true
            if ((total[l] ?: 0) != (derefUses[l] ?: 0) + (copyUses[l] ?: 0)) return false
            val to = copyTo[l] ?: return true
            for (t in to) if (!onlyDerefLocal(t, seen)) return false
            return true
        }

        /** Erased -> Unchecked once the guards are placed. */
        class Finish : MirTransform() {
            override fun expr(e: MirExpr): MirExpr {
                val r = rebuildExpr(e)
                return if (r is MirExpr.Cast && r.mode == MirExpr.CastMode.Erased) MirExpr.Cast(r.operand, r.to, MirExpr.CastMode.Unchecked) else r
            }
        }

        fun collect(body: List<MirStmt>, out: MutableList<Pair<MirLocal, MirExpr>>) {
            for (s in body) when (s) {
                is MirStmt.Declare -> { val i = s.init; if (i != null) out.add(Pair(s.local, i)) }
                is MirStmt.Assign -> out.add(Pair(s.local, s.value))
                is MirStmt.If -> { collect(s.thenBody, out); collect(s.elseBody, out) }
                is MirStmt.Block -> collect(s.body, out)
                is MirStmt.While -> collect(s.body, out)
                is MirStmt.Loop -> collect(s.body, out)
                is MirStmt.DoWhile -> { collect(s.body, out); collect(s.condStmts, out) }
                is MirStmt.ForRange -> collect(s.body, out)
                is MirStmt.Try -> { collect(s.body, out); for (c in s.catches) collect(c.body, out) }
                else -> {}
            }
        }

        fun assignedIn(body: List<MirStmt>, out: MutableSet<MirLocal>) {
            for (s in body) when (s) {
                is MirStmt.Assign -> out.add(s.local)
                is MirStmt.If -> { assignedIn(s.thenBody, out); assignedIn(s.elseBody, out) }
                is MirStmt.Block -> assignedIn(s.body, out)
                is MirStmt.While -> assignedIn(s.body, out)
                is MirStmt.Loop -> assignedIn(s.body, out)
                is MirStmt.DoWhile -> { assignedIn(s.body, out); assignedIn(s.condStmts, out) }
                is MirStmt.ForRange -> assignedIn(s.body, out)
                is MirStmt.Try -> { assignedIn(s.body, out); for (c in s.catches) assignedIn(c.body, out) }
                else -> {}
            }
        }

        fun vnOf(l: MirLocal): Int {
            val v = vn[l]
            if (v != null) return v
            val n = nextVn++
            vn[l] = n
            return n
        }

        /** True when [e] (of a non-null type) may be null: a retyped erased value, or a tainted local that is not known to be checked. */
        fun taint(e: MirExpr, useClean: Boolean): Boolean = when (e) {
            is MirExpr.Cast -> when (e.mode) {
                MirExpr.CastMode.Erased -> !readsCleanArray(e.operand)
                MirExpr.CastMode.Unchecked -> isNonNullRef(e.to) && taint(e.operand, useClean)
                else -> false
            }
            is MirExpr.Local -> e.local in taintedLocals && isNonNullRef(e.type) && !(useClean && vnOf(e.local) in cleanVn)
            is MirExpr.Conditional -> isNonNullRef(e.type) && (taint(e.thenValue, useClean) || taint(e.elseValue, useClean))
            else -> false
        }

        /** [o] is an element read from an array that cannot hold a null (see [cleanArrays]), directly or through the temporary that holds it. */
        fun readsCleanArray(o: MirExpr): Boolean {
            val src = if (o is MirExpr.Local) singleInit[o.local] else o
            if (src !is MirExpr.RuntimeCall || src.args.isEmpty()) return false
            if (src.cName != "mk_array_get_obj" && !src.cName.startsWith("mk_ref_array_get")) return false
            val b = baseLocal(src.args[0])
            return b != null && b in cleanArrays
        }

        /** [e] where it must not be null, through the retypings that hide it (the value stored into an array is passed as an `Any?`). */
        fun gDeep(e: MirExpr): MirExpr {
            if (e is MirExpr.Cast && e.mode == MirExpr.CastMode.Unchecked) {
                val i = gDeep(e.operand)
                return if (i === e.operand) e else MirExpr.Cast(i, e.to, MirExpr.CastMode.Unchecked)
            }
            return g(e)
        }

        fun baseLocal(e: MirExpr): MirLocal? = when {
            e is MirExpr.Local -> e.local
            e is MirExpr.Cast && e.mode == MirExpr.CastMode.Unchecked -> baseLocal(e.operand)
            else -> null
        }

        /** [e] where it must not be null: a NotNull when it may be. */
        fun g(e: MirExpr): MirExpr {
            if (!taint(e, false)) return e
            val b = baseLocal(e)
            if (counting && b != null) derefUses[b] = (derefUses[b] ?: 0) + 1
            if (!taint(e, true)) return e
            if (conditional == 0 && b != null) pending.add(vnOf(b))
            return MirExpr.NotNull(e)
        }

        fun commit() {
            if (pending.isEmpty()) return
            for (v in pending) cleanVn.add(v)
            pending.clear()
        }

        /** The local [l] (a tainted one) now holds [v]; null: declared and not yet assigned. */
        fun note(l: MirLocal, v: MirExpr?) {
            if (l !in taintedLocals) return
            if (v == null) { vn[l] = nextVn++; cleanVn.add(vn[l]!!); return }
            val b = baseLocal(v)
            if (b != null && b in taintedLocals && isNonNullRef(b.type)) {
                if (counting) {
                    copyUses[b] = (copyUses[b] ?: 0) + 1
                    var to = copyTo[b]
                    if (to == null) { to = ArrayList(); copyTo[b] = to }
                    to.add(l)
                }
                vn[l] = vnOf(b)
                return
            }
            val n = nextVn++
            vn[l] = n
            if (!taint(v, true)) cleanVn.add(n)
        }

        fun args(l: List<MirExpr>, types: List<MirType>?, from: Int = 0): List<MirExpr> {
            var out: ArrayList<MirExpr>? = null
            for (i in l.indices) {
                val a = l[i]
                val t = types?.getOrNull(i + from)
                val r = if (types == null || t == null || isNonNullRef(t)) g(a) else a
                if (out == null && r !== a) {
                    out = ArrayList(l.size)
                    for (k in 0 until i) out.add(l[k])
                }
                if (out != null) out.add(r)
            }
            return out ?: l
        }

        fun paramTypes(cName: String, dropThis: Boolean): List<MirType>? {
            val c = fns[cName] ?: return null
            val ps = c.params.map { it.type }
            return if (dropThis) ps.drop(1) else ps
        }

        override fun expr(e: MirExpr): MirExpr {
            when (e) {
                is MirExpr.Conditional -> {
                    val c = expr(e.cond)
                    conditional++
                    val t = expr(e.thenValue)
                    val el = expr(e.elseValue)
                    conditional--
                    return if (c === e.cond && t === e.thenValue && el === e.elseValue) e else MirExpr.Conditional(c, t, el, e.resultType)
                }
                is MirExpr.Binary -> if (e.op == BinaryOp.AndAnd || e.op == BinaryOp.OrOr) {
                    val l = expr(e.left)
                    conditional++
                    val r = expr(e.right)
                    conditional--
                    return if (l === e.left && r === e.right) e else MirExpr.Binary(e.op, l, r)
                }
                else -> {}
            }
            val r = rebuildExpr(e)
            return when (r) {
                is MirExpr.FieldGet -> { val o = g(r.obj); if (o === r.obj) r else MirExpr.FieldGet(o, r.owner, r.field, r.type) }
                is MirExpr.StringLength -> { val o = g(r.operand); if (o === r.operand) r else MirExpr.StringLength(o) }
                is MirExpr.VCall -> {
                    val rc = g(r.receiver)
                    val a = args(r.args, r.paramTypes)
                    if (rc === r.receiver && a === r.args) r else MirExpr.VCall(r.slot, rc, a, r.paramTypes, r.type)
                }
                is MirExpr.ICall -> {
                    val rc = g(r.receiver)
                    val a = args(r.args, r.paramTypes)
                    if (rc === r.receiver && a === r.args) r else MirExpr.ICall(r.interfaceCName, r.index, rc, a, r.paramTypes, r.type)
                }
                is MirExpr.Call -> { val a = args(r.args, paramTypes(r.cName, false)); if (a === r.args) r else MirExpr.Call(r.cName, a, r.type) }
                is MirExpr.New -> { val a = args(r.args, paramTypes(r.ctorCName, true)); if (a === r.args) r else MirExpr.New(r.ctorCName, a, r.type, r.allocator, r.auto) }
                is MirExpr.RuntimeCall -> if (r.cName == "mk_array_set_obj" && r.args.size == 3) store(r) else if (tolerant(r.cName)) r else { val a = args(r.args, null); if (a === r.args) r else MirExpr.RuntimeCall(r.cName, a, r.type) }
                else -> r
            }
        }

        /**
         * A store into an object array. Through a view that knows a non-null element type a value that may be null is checked here (a null
         * must not get into an array that was created without null elements). Through a view that allows null, a value that may be null is
         * stored by `mk_array_store_obj`, which compares it with the element type the array was created with.
         */
        fun store(r: MirExpr.RuntimeCall): MirExpr {
            val at = r.args[0].type as? TObject
            val el = at?.elem
            if (at == null || el == null || at.elemOut) return r
            val v = r.args[2]
            if (!arrayElemNullable(el)) {
                val v2 = gDeep(v)
                return if (v2 === v) r else MirExpr.RuntimeCall(r.cName, listOf(r.args[0], r.args[1], v2), r.type)
            }
            val pv = stripCasts(v)
            if (nullableTyped(pv.type) || taint(pv, true)) return MirExpr.RuntimeCall("mk_array_store_obj", r.args, r.type)
            return r
        }

        // ---- the look-ahead of an eager check: does anything observable happen before [l] is dereferenced? ----

        /** The operands of [e] that are dereferenced (or passed where a non-null value is expected). */
        fun derefOperands(e: MirExpr): List<MirExpr> = when (e) {
            is MirExpr.FieldGet -> listOf(e.obj)
            is MirExpr.StringLength -> listOf(e.operand)
            is MirExpr.VCall -> listOf(e.receiver)
            is MirExpr.ICall -> listOf(e.receiver)
            is MirExpr.Call -> e.args
            is MirExpr.New -> e.args
            is MirExpr.RuntimeCall -> if (tolerant(e.cName)) emptyList() else e.args
            else -> emptyList()
        }

        fun hasEffect(e: MirExpr): Boolean = when (e) {
            is MirExpr.Call, is MirExpr.New, is MirExpr.VCall, is MirExpr.ICall, is MirExpr.CCall, is MirExpr.CCallIndirect,
            is MirExpr.ObjectGet, is MirExpr.Unreachable, is MirExpr.CaughtException -> true
            is MirExpr.RuntimeCall -> !pureRuntime(e.cName)
            is MirExpr.Cast -> e.mode == MirExpr.CastMode.Checked
            is MirExpr.Binary -> (e.op == BinaryOp.Div || e.op == BinaryOp.Rem) && !(e.right is MirExpr.IntConst || e.right is MirExpr.LongConst)
            else -> false
        }

        fun scanExpr(e: MirExpr, al: HashSet<MirLocal>): Int {
            for (c in mirChildren(e)) {
                val r = scanExpr(c, al)
                if (r != NONE) return r
            }
            for (o in derefOperands(e)) {
                val b = baseLocal(o)
                if (b != null && b in al) return FOUND
            }
            return if (hasEffect(e)) IMPURE else NONE
        }

        fun scanList(l: List<MirStmt>, from: Int, al: HashSet<MirLocal>): Int {
            for (i in from until l.size) {
                val r = scanStmt(l[i], al)
                if (r != NONE) return r
            }
            return NONE
        }

        fun scanOpt(e: MirExpr?, al: HashSet<MirLocal>): Int = if (e == null) NONE else scanExpr(e, al)

        fun scanStmt(s: MirStmt, al: HashSet<MirLocal>): Int = when (s) {
            is MirStmt.Declare -> {
                val i = s.init
                val r = scanOpt(i, al)
                if (r == NONE && i != null) {
                    val b = baseLocal(i)
                    if (b != null && b in al && s.local in taintedLocals) al.add(s.local)
                }
                r
            }
            is MirStmt.Assign -> {
                val r = scanExpr(s.value, al)
                if (r == NONE) {
                    val b = baseLocal(s.value)
                    if (b != null && b in al && s.local in taintedLocals) al.add(s.local)
                }
                r
            }
            is MirStmt.SetField -> {
                var r = scanExpr(s.obj, al)
                if (r == NONE) r = scanExpr(s.value, al)
                if (r == NONE) {
                    val b = baseLocal(s.obj)
                    r = if (b != null && b in al) FOUND else IMPURE
                }
                r
            }
            is MirStmt.Eval -> scanExpr(s.expr, al)
            is MirStmt.Block -> scanList(s.body, 0, al)
            is MirStmt.If -> {
                var r = scanExpr(s.cond, al)
                if (r == NONE) {
                    val a = scanList(s.thenBody, 0, al)
                    val b = if (a == IMPURE) IMPURE else scanList(s.elseBody, 0, al)
                    r = if (a == IMPURE || b == IMPURE) IMPURE else if (a == FOUND || b == FOUND) FOUND else NONE
                }
                r
            }
            is MirStmt.ForRange -> {
                var r = scanExpr(s.start, al)
                if (r == NONE) r = scanExpr(s.end, al)
                if (r == NONE) r = scanOpt(s.step, al)
                if (r == NONE) r = scanList(s.body, 0, al)
                r
            }
            is MirStmt.While -> {
                var r = scanExpr(s.cond, al)
                if (r == NONE) r = scanList(s.body, 0, al)
                r
            }
            is MirStmt.Loop -> scanList(s.body, 0, al)
            is MirStmt.Break, is MirStmt.Continue -> NONE
            else -> IMPURE // a store, `try`, `return`, `throw`: the order is observable
        }

        /** True when the value [init] assigned to [l] at the statement [curIdx] of [curList] may be checked right there. */
        fun eager(l: MirLocal, init: MirExpr): Boolean {
            if (l !in onlyDeref || !taint(init, true)) return false
            val al = HashSet<MirLocal>()
            al.add(l)
            return scanList(curList, curIdx + 1, al) == FOUND
        }

        // ---- statements ----

        override fun stmts(l: List<MirStmt>): List<MirStmt> {
            val savedList = curList
            val savedIdx = curIdx
            var out: ArrayList<MirStmt>? = null
            for (i in l.indices) {
                val s = l[i]
                curList = l
                curIdx = i
                val r = stmt(s)
                if (out == null && r !== s) {
                    out = ArrayList(l.size)
                    for (k in 0 until i) out.add(l[k])
                }
                if (out != null) out.add(r)
            }
            curList = savedList
            curIdx = savedIdx
            return out ?: l
        }

        override fun stmt(s: MirStmt): MirStmt {
            val r = stmtInner(s)
            commit()
            return r
        }

        fun snapshot(): Pair<HashMap<MirLocal, Int>, HashSet<Int>> = Pair(HashMap(vn), HashSet(cleanVn))

        fun restore(snap: Pair<HashMap<MirLocal, Int>, HashSet<Int>>) {
            vn = HashMap(snap.first)
            cleanVn = HashSet(snap.second)
        }

        /** After a statement with nested bodies: what was known before, without the locals the bodies assigned. */
        fun afterNested(snap: Pair<HashMap<MirLocal, Int>, HashSet<Int>>, s: MirStmt) {
            restore(snap)
            val assigned = HashSet<MirLocal>()
            assignedIn(listOf(s), assigned)
            for (l in assigned) vn.remove(l)
        }

        fun stmtInner(s: MirStmt): MirStmt = when (s) {
            is MirStmt.Declare -> {
                val i = s.init
                var n = if (i == null) null else expr(i)
                if (n != null && !counting && eager(s.local, n)) n = MirExpr.NotNull(n)
                note(s.local, n)
                if (n === i) s else keep(s, MirStmt.Declare(s.local, n))
            }
            is MirStmt.Assign -> {
                var v = expr(s.value)
                if (!counting && eager(s.local, v)) v = MirExpr.NotNull(v)
                note(s.local, v)
                if (v === s.value) s else keep(s, MirStmt.Assign(s.local, v))
            }
            is MirStmt.SetField -> {
                val o = g(expr(s.obj))
                var v = expr(s.value)
                if (isNonNullRef(s.fieldType)) v = g(v)
                if (o === s.obj && v === s.value) s else keep(s, MirStmt.SetField(o, s.owner, s.field, s.fieldType, v))
            }
            is MirStmt.Return -> {
                val v = s.value
                var n: MirExpr? = null
                if (v != null) {
                    n = expr(v)
                    if (isNonNullRef(f.returnType)) n = g(n)
                }
                if (n === v) s else keep(s, MirStmt.Return(n))
            }
            is MirStmt.Throw -> { val v = g(expr(s.value)); if (v === s.value) s else keep(s, MirStmt.Throw(v)) }
            is MirStmt.If -> {
                val c = expr(s.cond)
                commit()
                val snap = snapshot()
                val t = stmts(s.thenBody)
                restore(snap)
                val el = stmts(s.elseBody)
                afterNested(snap, s)
                if (c === s.cond && t === s.thenBody && el === s.elseBody) s else keep(s, MirStmt.If(c, t, el))
            }
            is MirStmt.While -> {
                val snap = snapshot()
                afterNested(snap, s) // the head: what was known before, without what the body assigns
                val head = snapshot()
                val c = expr(s.cond)
                commit()
                val b = stmts(s.body)
                restore(head)
                if (c === s.cond && b === s.body) s else keep(s, MirStmt.While(s.label, c, b))
            }
            is MirStmt.Loop -> {
                afterNested(snapshot(), s)
                val head = snapshot()
                val b = stmts(s.body)
                restore(head)
                if (b === s.body) s else keep(s, MirStmt.Loop(s.label, b))
            }
            is MirStmt.DoWhile -> {
                afterNested(snapshot(), s)
                val head = snapshot()
                val b = stmts(s.body)
                restore(head) // `continue` skips the rest of the body
                val cs = stmts(s.condStmts)
                val c = expr(s.cond)
                commit()
                restore(head)
                if (b === s.body && cs === s.condStmts && c === s.cond) s else keep(s, MirStmt.DoWhile(s.label, b, cs, c))
            }
            is MirStmt.ForRange -> {
                val a = expr(s.start)
                val e2 = expr(s.end)
                val st = s.step
                val st2 = if (st == null) null else expr(st)
                commit()
                afterNested(snapshot(), s)
                val head = snapshot()
                val b = stmts(s.body)
                restore(head)
                if (a === s.start && e2 === s.end && st2 === st && b === s.body) s
                else keep(s, MirStmt.ForRange(s.label, s.variable, a, e2, st2, s.ascending, s.inclusive, b))
            }
            is MirStmt.Try -> {
                val before = snapshot()
                afterNested(before, MirStmt.Block(s.body)) // a throw leaves the body anywhere: the handlers know what was checked before it
                val atCatch = snapshot()
                restore(before)
                val b = stmts(s.body)
                var changed = b !== s.body
                val cs = ArrayList<MirCatch>()
                for (c in s.catches) {
                    restore(atCatch)
                    val cb = stmts(c.body)
                    if (cb !== c.body) { changed = true; cs.add(MirCatch(c.type, cb)) } else cs.add(c)
                }
                afterNested(before, s)
                if (!changed) s else keep(s, MirStmt.Try(b, cs))
            }
            else -> rebuildStmt(s)
        }
    }
}
