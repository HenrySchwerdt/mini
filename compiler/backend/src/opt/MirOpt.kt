package mini.backend

/*
 * The MIR optimization pipeline: runs on the linked, pruned, devirtualized whole-program module, before the emitters (and RcOpt).
 * Every pass has an off switch (`-Xno-<name>` on minic-native and minic); `-Xno-mir-opt` disables all of them, `-Xopt-stats`
 * prints what each pass did to stderr.
 */
class MirOptions {
    val disabled = HashSet<String>()
    var stats = false

    fun on(pass: String): Boolean = !disabled.contains("mir-opt") && !disabled.contains(pass)

    /** True when [arg] was an optimization option (and is now recorded). */
    fun accept(arg: String): Boolean {
        if (arg == "-Xopt-stats") {
            stats = true
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
        if (options.on("inline")) {
            val st = Inliner.Stats()
            m = Inliner.run(m, st)
            if (options.stats) report("inline: " + st.sites + " call sites in " + st.functions + " functions, " + st.exactDevirt + " exact-type devirtualizations, " + st.scalarReplaced + " objects scalar-replaced")
        }
        return m
    }
}
