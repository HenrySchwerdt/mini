package mini.backend

/*
 * Map lookups with a substring key, without the substring: `val w = s.substring(a, b); map[w]; map[w] = c + 1`.
 *
 * A local `w` that holds `s.substring(a, b)` and is only ever used as the key of `HashMap.findNode` (get, containsKey, getOrDefault ...,
 * after inlining) and `HashMap.put` is not made. The lookups call `findNodeRange(s, a, b)` (the hash and the comparison read the characters
 * of `s` in place) and `putRange(s, a, b, value)` (the key String is made only when the entry is new). What stays where the substring was:
 * the range test of `substring` (`checkSubstringRange`: the same IndexOutOfBoundsException at the same statement, before anything the
 * later statements do).
 *
 * Why it is sound
 *  - Strings are immutable, so the characters `s[a until b]` are what `w` would hold, as long as `s`, `a` and `b` have the values they had
 *    where `w` was declared. An operand that is a local never assigned in the function is used as it is; every other operand is copied
 *    into a fresh local at the declaration (left to right, like the call's arguments were evaluated).
 *  - `w` must not escape: every occurrence of `w` (and of locals only assigned from it by `val k = w as Any?`) has to be the key argument
 *    of one of the two callees. Any other use (passed on, stored, returned, captured, printed) leaves the whole local alone.
 *  - `put` is the only place the key is kept; `putRange` makes it when the key is not in the map yet.
 * Needs the helper functions in the stdlib (HashMap.findNodeRange / putRange, kotlin.text.checkSubstringRange): the program is only
 * rewritten when the linked module has them (MirLink keeps them alive next to `findNode` / `put` and `substring`).
 * Not applied to Strict functions.
 */
internal class SubstrKey(module: MirModule) {
    var sites = 0
    private val substr: String?
    private val check: String?
    private val find: String?
    private val findRange: String?
    private val put: String?
    private val putRange: String?
    val available: Boolean

    init {
        var sub: String? = null
        var chk: String? = null
        var fnd: String? = null
        var fndR: String? = null
        var pt: String? = null
        var ptR: String? = null
        for (f in module.functions) {
            when (f.qualifiedName) {
                "kotlin.text.substring" -> if (f.params.size == 3 && f.params[0].type === TString && f.params[1].type === TInt && f.params[2].type === TInt) sub = f.cName
                "kotlin.text.checkSubstringRange" -> if (f.params.size == 3) chk = f.cName
                "kotlin.collections.HashMap.findNode" -> if (f.params.size == 2) fnd = f.cName
                "kotlin.collections.HashMap.findNodeRange" -> if (f.params.size == 4) fndR = f.cName
                "kotlin.collections.HashMap.put" -> if (f.params.size == 3) pt = f.cName
                "kotlin.collections.HashMap.putRange" -> if (f.params.size == 5) ptR = f.cName
            }
        }
        substr = sub; check = chk; find = fnd; findRange = fndR; put = pt; putRange = ptR
        available = sub != null && chk != null && fnd != null && fndR != null && pt != null && ptR != null
    }

    private class Cand(val decl: MirStmt.Declare, val s: MirExpr, val a: MirExpr, val b: MirExpr) {
        val group = HashSet<Int>()
        var occ = 0
        var allowed = 0
        var uses = 0
        var bad = false
        // the operands the lookups use (set when the candidate is accepted)
        var rs: MirExpr? = null
        var ra: MirExpr? = null
        var rb: MirExpr? = null
    }

    /** One pass over the body: the candidates, the assigned locals, the occurrences of each local and the allowed ones. */
    private inner class Scan(val f: MirFunction) : MirVisitor() {
        val cands = HashMap<Int, Cand>()          // w local id -> candidate
        val assigned = HashSet<Int>()
        val occ = HashMap<Int, Int>()
        val aliasOf = HashMap<Int, Int>()         // k -> g for `declare k = g` / `declare k = (g as Any?)`
        val allowedOcc = HashMap<Int, Int>()      // local id -> occurrences that are an alias declaration or a key argument
        val keyUses = HashMap<Int, Int>()         // local id -> key arguments
        val aliasDecls = HashMap<Int, MirStmt.Declare>()
        var maxLocal = -1

        init {
            for (p in f.params) if (p.id > maxLocal) maxLocal = p.id
            walk(f.body)
        }

        fun unwrap(e: MirExpr): MirExpr {
            var x = e
            while (x is MirExpr.Cast && x.mode != MirExpr.CastMode.Checked && x.mode != MirExpr.CastMode.Safe) x = x.operand
            return x
        }

        fun allow(id: Int) { allowedOcc[id] = (allowedOcc[id] ?: 0) + 1 }

        override fun visit(e: MirExpr) {
            when (e) {
                is MirExpr.Local -> { occ[e.local.id] = (occ[e.local.id] ?: 0) + 1 }
                is MirExpr.Call -> {
                    if (e.cName == find && e.args.size == 2) {
                        val k = unwrap(e.args[1])
                        if (k is MirExpr.Local) { allow(k.local.id); keyUses[k.local.id] = (keyUses[k.local.id] ?: 0) + 1 }
                    } else if (e.cName == put && e.args.size == 3) {
                        val k = unwrap(e.args[1])
                        if (k is MirExpr.Local) { allow(k.local.id); keyUses[k.local.id] = (keyUses[k.local.id] ?: 0) + 1 }
                    }
                }
                else -> {}
            }
        }

        override fun stmt(s: MirStmt): MirStmt {
            when (s) {
                is MirStmt.Declare -> {
                    if (s.local.id > maxLocal) maxLocal = s.local.id
                    val init = s.init
                    if (init is MirExpr.Call && init.cName == substr && init.args.size == 3 && s.local.type === TString) {
                        cands[s.local.id] = Cand(s, init.args[0], init.args[1], init.args[2])
                    } else if (init != null) {
                        val u = unwrap(init)
                        if (u is MirExpr.Local && u.local.type.isRef && (s.local.type === TString || s.local.type === TNString || s.local.type is TObject)) {
                            aliasOf[s.local.id] = u.local.id
                            aliasDecls[s.local.id] = s
                            allow(u.local.id)
                        }
                    }
                }
                is MirStmt.Assign -> assigned.add(s.local.id)
                is MirStmt.ForRange -> { assigned.add(s.variable.id); if (s.variable.id > maxLocal) maxLocal = s.variable.id }
                else -> {}
            }
            return super.stmt(s)
        }
    }

    fun run(f: MirFunction): MirFunction {
        if (!available || f.strict) return f
        val scan = Scan(f)
        if (scan.cands.isEmpty()) return f
        // the group of every candidate: itself and the locals that are (transitively) plain copies of it
        val rootOf = HashMap<Int, Int>()
        for (k in scan.aliasOf.keys) {
            var g = scan.aliasOf[k]!!
            var n = 0
            while (g !in scan.cands && scan.aliasOf.containsKey(g) && n < 50) { g = scan.aliasOf[g]!!; n++ }
            if (g in scan.cands) rootOf[k] = g
        }
        // an alias that is itself assigned, or a copy whose own occurrences are not all keys, spoils the group
        for ((w, c) in scan.cands) {
            c.group.add(w)
            for ((k, r) in rootOf) if (r == w) c.group.add(k)
        }
        // locals copied from a group member that are not themselves in a group (e.g. an alias of an alias that was not resolved) show up as occurrences
        val accepted = ArrayList<Cand>()
        for ((_, c) in scan.cands) {
            var occ = 0
            var allowed = 0
            var uses = 0
            var bad = false
            for (g in c.group) {
                occ += scan.occ[g] ?: 0
                allowed += scan.allowedOcc[g] ?: 0
                uses += scan.keyUses[g] ?: 0
                if (g in scan.assigned) bad = true
            }
            if (!bad && uses > 0 && occ == allowed) accepted.add(c)
        }
        if (accepted.isEmpty()) return f
        var next = scan.maxLocal + 1
        fun fresh(name: String, t: MirType) = MirLocal(next++, name, t)
        val byLocal = HashMap<Int, Cand>()                 // every group member -> candidate
        val declOf = HashMap<MirStmt, List<MirStmt>>()     // the statements that replace a declaration
        for (c in accepted) {
            for (g in c.group) byLocal[g] = c
            val out = ArrayList<MirStmt>()
            fun operand(e: MirExpr, name: String): MirExpr {
                val stable = (e is MirExpr.Local && e.local.id !in scan.assigned) || e is MirExpr.IntConst
                if (stable) return e
                val t = fresh(name, e.type)
                out.add(MirStmt.Declare(t, e))
                return MirExpr.Local(t)
            }
            c.rs = operand(c.s, "rkSrc")
            c.ra = operand(c.a, "rkFrom")
            c.rb = operand(c.b, "rkTo")
            out.add(MirStmt.Eval(MirExpr.Call(check!!, listOf(copy(c.rs!!), copy(c.ra!!), copy(c.rb!!)), TUnit)))
            for (st in out) { st.srcLine = c.decl.srcLine; st.srcFile = c.decl.srcFile }
            declOf[c.decl] = out
            sites++
        }
        val removed = HashSet<MirStmt>()
        for (c in accepted) for (g in c.group) scan.aliasDecls[g]?.let { removed.add(it) }

        val rw = object : MirTransform() {
            override fun stmts(l: List<MirStmt>): List<MirStmt> {
                var out: ArrayList<MirStmt>? = null
                for (i in l.indices) {
                    val s = l[i]
                    val repl: List<MirStmt>? = if (s is MirStmt.Declare) (declOf[s] ?: if (s in removed) emptyList() else null) else null
                    val r = if (repl != null) null else stmt(s)
                    if (out == null && (repl != null || r !== s)) {
                        out = ArrayList(l.size)
                        for (k in 0 until i) out.add(l[k])
                    }
                    if (out != null) { if (repl != null) out.addAll(repl) else out.add(r!!) }
                }
                return out ?: l
            }

            private fun cand(key: MirExpr): Cand? {
                var x = key
                while (x is MirExpr.Cast && x.mode != MirExpr.CastMode.Checked && x.mode != MirExpr.CastMode.Safe) x = x.operand
                return if (x is MirExpr.Local) byLocal[x.local.id] else null
            }

            override fun expr(e: MirExpr): MirExpr {
                if (e is MirExpr.Call) {
                    if (e.cName == find && e.args.size == 2) {
                        val c = cand(e.args[1])
                        if (c != null) return MirExpr.Call(findRange!!, listOf(expr(e.args[0]), copy(c.rs!!), copy(c.ra!!), copy(c.rb!!)), e.type)
                    } else if (e.cName == put && e.args.size == 3) {
                        val c = cand(e.args[1])
                        if (c != null) return MirExpr.Call(putRange!!, listOf(expr(e.args[0]), copy(c.rs!!), copy(c.ra!!), copy(c.rb!!), expr(e.args[2])), e.type)
                    }
                }
                return rebuildExpr(e)
            }
        }
        return f.withBody(rw.stmts(f.body))
    }

    private fun copy(e: MirExpr): MirExpr = when (e) {
        is MirExpr.Local -> MirExpr.Local(e.local)
        is MirExpr.IntConst -> MirExpr.IntConst(e.value)
        else -> e
    }
}
