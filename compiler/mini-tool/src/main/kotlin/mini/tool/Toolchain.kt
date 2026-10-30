package mini.tool

/**
 * Where the compiler lives. `mini` talks to the native compiler `minic-native` through a process boundary only (like cargo
 * and rustc): Kotlin to C by `minic-native`, then the C compiler links the result with the prebuilt runtime. No JVM anywhere.
 *
 * One layout, the same in a release archive, in `~/.mini` and in a source checkout after `scripts/build-mini.sh`
 * (which stages it under `<repo>/build/native`):
 *
 *     <root>/bin/minic-native   <root>/bin/mini
 *     <root>/lib/libminirt.a    <root>/include/minirt.h
 *     <root>/stdlib/            the standard library sources, compiled together with every program
 *
 * [root] comes from `MINI_ROOT` (the `./mini` wrapper sets it), else from the location of the running `mini` binary.
 */
class Toolchain(
    val root: String,
    val minic: String,
    val cc: String,
    val ccName: String,
) {
    val stdlib: String get() = Paths.join(root, "stdlib")
    val include: String get() = Paths.join(root, "include")
    val runtimeLib: String get() = Paths.join(root, "lib/libminirt.a")

    companion object {
        const val MINIC_RELATIVE = "bin/minic-native"

        private fun isHome(p: Platform, dir: String): Boolean =
            p.isFile(Paths.join(dir, "include/minirt.h")) && p.isDirectory(Paths.join(dir, "stdlib"))

        /** The installation root: `$MINI_ROOT`, else the directory above the directory of the running `mini`; null when neither is one. */
        fun findRoot(p: Platform, exePath: String? = null): String? {
            p.env("MINI_ROOT")?.takeIf { it.isNotEmpty() }?.let { return Paths.normalize(it) }
            val exe = exePath ?: p.exePath() ?: return null
            val up = Paths.parent(exe)?.let { Paths.parent(it) } ?: return null
            return if (isHome(p, up)) up else null
        }

        /** The C compiler: `$MINI_CC`, `$CC`, then clang, then gcc. Returns (command, display name) or null. */
        fun findCompiler(p: Platform): Pair<String, String>? {
            val candidates = listOfNotNull(p.env("MINI_CC"), p.env("CC"), "clang", "gcc").filter { it.isNotEmpty() }
            for (cand in candidates) {
                val r = p.exec(Exec(listOf(cand, "--version")))
                if (r.exitCode == 0) return Pair(cand, r.stdout.lines().firstOrNull()?.trim() ?: cand)
            }
            return null
        }

        fun locate(p: Platform): Toolchain {
            val root = findRoot(p) ?: throw MiniError(
                "cannot find the Mini installation (stdlib/, include/ and lib/)",
                "install Mini (see the README), or in a source checkout run `./mini` (it builds the toolchain), or set MINI_ROOT",
            )
            if (!isHome(p, root)) {
                throw MiniError("MINI_ROOT=$root does not contain stdlib/ and include/minirt.h", "point MINI_ROOT at an installed Mini, or run `scripts/build-mini.sh` in a source checkout and use `build/native`")
            }
            val explicit = p.env("MINIC_NATIVE")?.takeIf { it.isNotEmpty() }
            val inTree = Paths.join(root, MINIC_RELATIVE)
            val minic = when {
                explicit != null -> explicit
                p.isFile(inTree) -> inTree
                else -> throw MiniError(
                    "the compiler `minic-native` is missing (${MINIC_RELATIVE} under $root)",
                    "reinstall Mini, or in a source checkout run `scripts/build-mini.sh`, or set MINIC_NATIVE to a minic-native binary",
                )
            }
            if (!p.isFile(Paths.join(root, "lib/libminirt.a"))) {
                throw MiniError("the runtime library lib/libminirt.a is missing under $root", "reinstall Mini, or in a source checkout run `scripts/build-mini.sh`")
            }
            val cc = findCompiler(p) ?: throw MiniError("no C compiler found (tried clang and gcc)", "install one (`sudo apt install clang` or `xcode-select --install`), then run `mini doctor`")
            return Toolchain(root, minic, cc.first, cc.first)
        }
    }

    /** Every `.kt` file of the standard library, ordered like `find | LC_ALL=C sort` (the order the compiler is bootstrapped with). */
    fun stdlibFiles(p: Platform): List<String> {
        val out = ArrayList<String>()
        fun walk(dir: String) {
            for (n in p.list(dir)) {
                val f = Paths.join(dir, n)
                if (p.isDirectory(f)) walk(f) else if (n.endsWith(".kt")) out += f
            }
        }
        walk(stdlib)
        return out.sorted()
    }

    /** Runs minic-native in [cwd] (default: the installation root). */
    fun runMinic(p: Platform, args: List<String>, cwd: String? = null): ExecResult =
        p.exec(Exec(listOf(minic) + args, cwd = cwd ?: root))

    /**
     * Kotlin to a native executable: `minic-native` writes C, the C compiler links it with `lib/libminirt.a`.
     * [sources] are the program (the first one names the module), [declarationOnly] files only declare (annotation classes,
     * other packages' APIs). The result merges the output of both steps; a non-zero exit code names the failing one.
     */
    fun compile(p: Platform, sources: List<String>, declarationOnly: List<String>, out: String, werror: Boolean, release: Boolean): ExecResult {
        val cFile = "$out.c"
        val args = ArrayList<String>()
        args += listOf("-o", cFile)
        if (werror) args += "-Werror"
        args += sources
        args += "--stdlib"
        args += stdlibFiles(p)
        args += declarationOnly
        val r = runMinic(p, args)
        if (r.exitCode != 0 || r.startError != null) return r
        val links = ArrayList<String>()
        val c = p.readText(cFile) ?: return ExecResult(1, r.stdout, r.stderr + "minic: the compiler wrote no C file\n", r.millis)
        for (line in c.lines()) {
            if (line.startsWith("/* mini:link ") && line.endsWith(" */")) links += "-l" + line.substring(13, line.length - 3)
        }
        val cmd = ArrayList<String>()
        cmd += listOf(cc, "-std=c17", "-O1", "-w", "-I", include)
        val dirs = ArrayList<String>()
        for (s in sources) {
            val d = Paths.parent(s) ?: continue
            if (d !in dirs) dirs += d
        }
        for (d in dirs) { cmd += "-I"; cmd += d }
        cmd += if (release) "-O2" else "-g"
        cmd += listOf(cFile, runtimeLib, "-o", out, "-lm")
        cmd += links
        val cr = p.exec(Exec(cmd))
        p.remove(cFile)
        if (cr.exitCode != 0 || cr.startError != null) {
            val msg = "minic: the C compiler failed (exit ${cr.exitCode}): ${cmd.joinToString(" ")}\n"
            return ExecResult(if (cr.exitCode == 0) 1 else cr.exitCode, r.stdout + cr.stdout, r.stderr + msg + cr.stderr, r.millis + cr.millis, cr.startError)
        }
        return ExecResult(0, r.stdout + cr.stdout, r.stderr + cr.stderr, r.millis + cr.millis)
    }

    /** The frontend and the memory checker for [file] only; [declarationOnly] files (the rest of the package) provide declarations. */
    fun check(p: Platform, file: String, declarationOnly: List<String>, werror: Boolean): ExecResult {
        val args = ArrayList<String>()
        args += "--check"
        if (werror) args += "-Werror"
        args += file
        args += "--stdlib"
        args += stdlibFiles(p)
        args += declarationOnly
        return runMinic(p, args)
    }

    /** A cheap stamp of the compiler installation, so that a new compiler or runtime rebuilds everything. */
    fun stamp(p: Platform): String {
        val h = Hasher()
        for (f in listOf(minic, runtimeLib, Paths.join(include, "minirt.h"))) {
            val s = p.stat(f)
            h.add(f).add(s?.size ?: -1L).add(s?.modifiedMillis ?: -1L)
        }
        stampTree(p, stdlib, h)
        h.add(cc)
        return h.hex()
    }

    private fun stampTree(p: Platform, dir: String, h: Hasher) {
        for (n in p.list(dir)) {
            val f = Paths.join(dir, n)
            if (p.isDirectory(f)) stampTree(p, f, h) else {
                val s = p.stat(f)
                h.add(f).add(s?.size ?: -1L).add(s?.modifiedMillis ?: -1L)
            }
        }
    }
}
