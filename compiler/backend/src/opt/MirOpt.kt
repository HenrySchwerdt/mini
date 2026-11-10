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

object MirOpt {
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
        if (options.on("array-len")) {
            val st = IntArray(1)
            m = ArrayLen.run(m, st)
            if (options.stats) report("array-len: " + st[0] + " array locals with a cached length")
            if (options.on("range-check")) {
                val rs = IntArray(1)
                m = RangeCheck.run(m, rs)
                if (options.stats) report("range-check: " + rs[0] + " loops versioned with unchecked array accesses")
            }
        }
        return m
    }
}
