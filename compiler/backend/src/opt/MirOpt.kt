package mini.backend

/*
 * The MIR optimization pipeline: runs on the linked, pruned, devirtualized whole-program module, before the emitters (and RcOpt).
 * Every pass has an off switch (`-Xno-<name>` on minic-native and minic); `-Xno-mir-opt` disables all of them, `-Xopt-stats`
 * prints what each pass did to stderr.
 */
class MirOptions {
    val disabled = HashSet<String>()
    var stats = false
    var layoutStats = false

    fun on(pass: String): Boolean = !disabled.contains("mir-opt") && !disabled.contains(pass)

    /** True when [arg] was an optimization option (and is now recorded). */
    fun accept(arg: String): Boolean {
        if (arg == "-Xopt-stats") {
            stats = true
            return true
        }
        if (arg == "-Xlayout-stats") {
            layoutStats = true
            return true
        }
        if (arg.startsWith("-Xno-")) {
            disabled.add(arg.substring(5))
            return true
        }
        return false
    }
}

/** `-Xno-prefetch`: drops the calls of the cache hint `refArrayPrefetch` (`mk_ref_array_prefetch`), a statement that only ever stands alone. */
private class DropPrefetch : MirTransform() {
    override fun stmts(l: List<MirStmt>): List<MirStmt> {
        var out: ArrayList<MirStmt>? = null
        for (i in l.indices) {
            val s = l[i]
            val drop = s is MirStmt.Eval && s.expr.let { it is MirExpr.RuntimeCall && it.cName == "mk_ref_array_prefetch" }
            val r = if (drop) null else stmt(s)
            if (out == null && (drop || r !== s)) {
                out = ArrayList(l.size)
                for (k in 0 until i) out.add(l[k])
            }
            if (out != null && r != null) out.add(r)
        }
        return out ?: l
    }
}

object MirOpt {
    fun withoutPrefetch(module: MirModule): MirModule {
        val t = DropPrefetch()
        val fs = ArrayList<MirFunction>(module.functions.size)
        var changed = false
        for (f in module.functions) {
            val body = t.stmts(f.body)
            if (body === f.body) fs.add(f) else { changed = true; fs.add(f.withBody(body)) }
        }
        return if (!changed) module else MirModule(module.sourceName, fs, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }

    /** `-Xno-always-inline`: [module] with the `kotlin.internal.AlwaysInline` request of every function cleared. */
    fun withoutAlwaysInline(module: MirModule): MirModule {
        if (module.functions.none { it.alwaysInline }) return module
        val fs = ArrayList<MirFunction>(module.functions.size)
        for (f in module.functions) {
            fs.add(if (!f.alwaysInline) f else MirFunction(
                f.qualifiedName, f.cName, f.params, f.returnType, f.body, f.constructorOf, f.strict, f.returnsBorrowed, f.ownsParams, f.noHeapEffects, false,
            ))
        }
        return MirModule(module.sourceName, fs, module.classes, module.externs, module.includes, module.links, module.wasmExports)
    }

    fun run(module: MirModule, options: MirOptions, report: (String) -> Unit): MirModule {
        var m = module
        if (options.on("inline") || options.on("sroa") || options.on("exact-devirt")) {
            val st = InlinerStats()
            m = Inliner.run(m, st, options)
            if (options.stats) report("inline: " + st.sites + " call sites in " + st.functions + " functions, " + st.exactDevirt + " exact-type devirtualizations, " + st.scalarReplaced + " objects and " + st.arraysReplaced + " arrays scalar-replaced")
            if (options.stats) report("inline timing ms: exact " + st.tExact / 1000000 + ", expand " + st.tExpand / 1000000 + ", cleanup " + st.tClean / 1000000 + ", sroa " + st.tSroa / 1000000)
        }
        if (options.on("cast-elim")) {
            val ce = CastElim(m)
            val fs = ArrayList<MirFunction>(m.functions.size)
            for (f in m.functions) fs.add(ce.run(f))
            if (options.stats) report("cast-elim: " + ce.removed + " checked casts that cannot fail")
            m = MirModule(m.sourceName, fs, m.classes, m.externs, m.includes, m.links, m.wasmExports)
        }
        if (options.on("exact-is")) {
            val ex = ExactIs(m)
            val fs = ArrayList<MirFunction>(m.functions.size)
            for (f in m.functions) fs.add(ex.run(f))
            if (options.stats) report("exact-is: " + ex.changed + " class tests of a leaf class turned into a type-word compare")
            m = MirModule(m.sourceName, fs, m.classes, m.externs, m.includes, m.links, m.wasmExports)
        }
        if (options.on("substr-key")) {
            val sk = SubstrKey(m)
            if (sk.available) {
                val fs = ArrayList<MirFunction>(m.functions.size)
                for (f in m.functions) fs.add(sk.run(f))
                if (options.stats) report("substr-key: " + sk.sites + " substrings used only as map keys are not made")
                m = MirModule(m.sourceName, fs, m.classes, m.externs, m.includes, m.links, m.wasmExports)
            }
        }
        if (options.on("array-len")) {
            val st = IntArray(1)
            m = ArrayLen.run(m, st, options.on("array-const-len"))
            if (options.stats) report("array-len: " + st[0] + " array locals with a cached length")
            if (options.on("range-check")) {
                val rs = IntArray(1)
                m = RangeCheck.run(m, rs, options.on("range-check-const"))
                if (options.stats) report("range-check: " + rs[0] + " loops versioned with unchecked array accesses")
            }
        }
        return m
    }
}
