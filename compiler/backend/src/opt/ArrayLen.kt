package mini.backend

/*
 * Array length caching. The length of an array never changes, but the C compiler cannot know that: an `Int` store into
 * an `IntArray` may alias the `Int` length field, so every bounds test in a loop that writes the array reloads the length
 * (and the test cannot be hoisted or merged). For a primitive-array local that is never reassigned (a parameter, or a
 * `val`-like local with an initializer) and is indexed inside a loop, the length is read once, right after the
 * declaration, into an `Int` local, and the accessors get it as an extra last argument:
 *
 *     mk_array_get_int(a, i)       ->  mk_array_get_int(a, i, len)        (3 arguments)
 *     mk_array_set_int(a, i, v)    ->  mk_array_set_int(a, i, v, len)     (4 arguments)
 *     mk_array_length(a)           ->  len
 *
 * The bounds test is unchanged (an unsigned compare against the length), so the checks stay; the emitters only skip the load.
 * Object arrays too (the reference counting of the accessors is decided later, by RcOpt, on the same calls).
 */
internal object ArrayLen {
    private val KINDS = setOf("int", "long", "double", "float", "byte", "short", "boolean", "char", "obj")

    fun isPrimArray(t: MirType): Boolean {
        if (t !is TObject || t.nullable) return false
        return t.cName == "MkIntArray" || t.cName == "MkLongArray" || t.cName == "MkDoubleArray" || t.cName == "MkFloatArray" ||
            t.cName == "MkByteArray" || t.cName == "MkShortArray" || t.cName == "MkBooleanArray" || t.cName == "MkCharArray" || t.cName == "MkObjectArray"
    }

    /** The element kind when [e] is a plain accessor (without a cached length) on a local, else null. */
    fun accessKind(e: MirExpr.RuntimeCall): String? {
        val n = e.cName
        val isGet = n.startsWith("mk_array_get_")
        if (!isGet && !n.startsWith("mk_array_set_")) return null
        val k = n.substring(13)
        if (k !in KINDS) return null
        if (e.args.size != (if (isGet) 2 else 3)) return null
        return k
    }

    /** The element kind when [e] is an accessor that carries a cached length (a local of type Int as the last argument), else null. */
    fun lenAccessKind(e: MirExpr.RuntimeCall): String? {
        val n = e.cName
        val isGet = n.startsWith("mk_array_get_")
        if (!isGet && !n.startsWith("mk_array_set_")) return null
        val k = n.substring(13)
        if (k !in KINDS) return null
        if (e.args.size != (if (isGet) 3 else 4)) return null
        val last = e.args[e.args.size - 1]
        return if (last is MirExpr.Local && last.type === TInt) k else null
    }

    /** The arguments of an accessor without the cached length (what a plain runtime call takes). */
    fun plainArgs(e: MirExpr.RuntimeCall): List<MirExpr> {
        val n = e.cName
        val k = if (n.startsWith("mk_array_get_") && e.args.size == 3) n.substring(13)
        else if (n.startsWith("mk_array_set_") && e.args.size == 4) n.substring(13) else return e.args
        return if (k in KINDS) e.args.subList(0, e.args.size - 1) else e.args
    }

    fun run(module: MirModule, stats: IntArray): MirModule {
        var out: ArrayList<MirFunction>? = null
        val fs = module.functions
        for (i in fs.indices) {
            val f = fs[i]
            val g = runFunction(f, stats)
            if (g !== f) {
                if (out == null) {
                    out = ArrayList(fs.size)
                    for (k in 0 until i) out.add(fs[k])
                }
            }
            if (out != null) out.add(g)
        }
        if (out == null) return module
        return MirModule(module.sourceName, out, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }

    private fun runFunction(f: MirFunction, stats: IntArray): MirFunction {
        if (f.strict) return f
        val scan = Scan(f)
        scan.walkBody(f.body)
        if (scan.hot.isEmpty()) return f
        val chosen = HashMap<Int, MirLocal>()
        val ids = IdScan(f)
        var next = ids.maxLocal + 1
        for (id in scan.hotOrder) {
            if ((scan.assigns[id] ?: 0) != 0) continue
            val l = scan.candidates[id] ?: continue
            chosen[id] = MirLocal(next++, l.name + "_len", TInt)
        }
        if (chosen.isEmpty()) return f
        stats[0] += chosen.size
        val rw = Rewrite(chosen)
        val body = rw.stmts(f.body)
        val pre = ArrayList<MirStmt>()
        for (p in f.params) {
            val len = chosen[p.id] ?: continue
            pre.add(MirStmt.Declare(len, MirExpr.RuntimeCall("mk_array_length", listOf(MirExpr.Local(p)), TInt)))
        }
        val nb = if (pre.isEmpty()) body else {
            val all = ArrayList<MirStmt>(pre.size + body.size)
            all.addAll(pre)
            all.addAll(body)
            all
        }
        return f.withBody(nb)
    }

    /** Finds candidates (parameters and initialised declarations of primitive arrays), reassigned locals and accesses inside loops. */
    private class Scan(val f: MirFunction) : MirVisitor() {
        val candidates = HashMap<Int, MirLocal>()
        val declDepth = HashMap<Int, Int>()
        val assigns = HashMap<Int, Int>()
        val hot = HashSet<Int>()
        val hotOrder = ArrayList<Int>()
        var depth = 0

        fun walkBody(b: List<MirStmt>) {
            for (p in f.params) if (isPrimArray(p.type)) { candidates[p.id] = p; declDepth[p.id] = 0 }
            stmts(b)
        }

        override fun visit(e: MirExpr) {
            if (e !is MirExpr.RuntimeCall || e.args.isEmpty()) return
            val a0 = e.args[0]
            if (a0 !is MirExpr.Local) return
            if (accessKind(e) == null) return
            val id = a0.local.id
            val dd = declDepth[id] ?: return
            if (depth > dd && hot.add(id)) hotOrder.add(id)
        }

        override fun stmt(s: MirStmt): MirStmt {
            when (s) {
                is MirStmt.Declare -> if (s.init != null && isPrimArray(s.local.type)) {
                    if (candidates.containsKey(s.local.id)) assigns[s.local.id] = 1 else { candidates[s.local.id] = s.local; declDepth[s.local.id] = depth }
                } else if (isPrimArray(s.local.type)) assigns[s.local.id] = 1
                is MirStmt.Assign -> assigns[s.local.id] = (assigns[s.local.id] ?: 0) + 1
                is MirStmt.ForRange -> assigns[s.variable.id] = (assigns[s.variable.id] ?: 0) + 1
                else -> {}
            }
            val loop = s is MirStmt.While || s is MirStmt.Loop || s is MirStmt.DoWhile || s is MirStmt.ForRange
            if (loop) depth++
            val r = super.stmt(s)
            if (loop) depth--
            return r
        }
    }

    private class Rewrite(val chosen: Map<Int, MirLocal>) : MirTransform() {
        override fun expr(e: MirExpr): MirExpr {
            val r = rebuildExpr(e)
            if (r !is MirExpr.RuntimeCall || r.args.isEmpty()) return r
            val a0 = r.args[0]
            if (a0 !is MirExpr.Local) return r
            val len = chosen[a0.local.id] ?: return r
            if (r.cName == "mk_array_length" && r.args.size == 1) return MirExpr.Local(len)
            if (accessKind(r) == null) return r
            val args = ArrayList<MirExpr>(r.args.size + 1)
            args.addAll(r.args)
            args.add(MirExpr.Local(len))
            return MirExpr.RuntimeCall(r.cName, args, r.type)
        }

        override fun stmts(l: List<MirStmt>): List<MirStmt> {
            var out: ArrayList<MirStmt>? = null
            for (i in l.indices) {
                val s = l[i]
                val r = stmt(s)
                if (out == null && (r !== s || (s is MirStmt.Declare && chosen.containsKey(s.local.id)))) {
                    out = ArrayList(l.size + 2)
                    for (k in 0 until i) out.add(l[k])
                }
                if (out != null) {
                    out.add(r)
                    if (s is MirStmt.Declare) {
                        val len = chosen[s.local.id]
                        if (len != null) out.add(keep(s, MirStmt.Declare(len, MirExpr.RuntimeCall("mk_array_length", listOf(MirExpr.Local(s.local)), TInt))))
                    }
                }
            }
            return out ?: l
        }
    }
}
