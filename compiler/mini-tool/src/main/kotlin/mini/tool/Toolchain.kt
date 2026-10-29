package mini.tool

/**
 * Where the compiler lives. `mini` talks to `minic` through a process boundary only (like cargo and rustc), so the
 * compiler can later be a native binary without changing anything here.
 *
 * Layout in a source checkout (also what `./mini` exports as MINI_ROOT): `<root>/stdlib`, `<root>/runtime/{src,include}`,
 * `<root>/compiler/jvm-bridge/build/install/minic/bin/minic`.
 */
class Toolchain(
    val root: String,
    val minic: String,
    val cc: String,
    val ccName: String,
) {
    val stdlib: String get() = Paths.join(root, "stdlib")
    val runtimeSrc: String get() = Paths.join(root, "runtime/src")
    val runtimeInclude: String get() = Paths.join(root, "runtime/include")

    companion object {
        const val MINIC_RELATIVE = "compiler/jvm-bridge/build/install/minic/bin/minic"

        fun findRoot(p: Platform): String? {
            p.env("MINI_ROOT")?.takeIf { it.isNotEmpty() }?.let { return Paths.normalize(it) }
            var d: String? = p.cwd()
            while (d != null) {
                if (p.isFile(Paths.join(d, "runtime/include/minirt.h")) && p.isDirectory(Paths.join(d, "stdlib"))) return d
                d = Paths.parent(d)
            }
            return null
        }

        /** The C compiler: `$MINI_CC`, `$CC`, then clang, then gcc. Returns (command, display name) or null. */
        fun findCompiler(p: Platform): Pair<String, String>? {
            val candidates = listOfNotNull(p.env("MINI_CC"), p.env("CC"), "clang", "gcc").filter { it.isNotEmpty() }
            for (cand in candidates) {
                val r = p.exec(Exec(listOf(cand, "--version")))
                if (r.exitCode == 0) return Pair(cand, r.stdout.lineSequence().firstOrNull()?.trim() ?: cand)
            }
            return null
        }

        fun locate(p: Platform): Toolchain {
            val root = findRoot(p) ?: throw MiniError(
                "cannot find the Mini installation (stdlib/ and runtime/)",
                "run `mini` through the wrapper in the repository (`<repo>/mini`), or set MINI_ROOT to the repository root",
            )
            if (!p.isDirectory(Paths.join(root, "stdlib")) || !p.isFile(Paths.join(root, "runtime/include/minirt.h"))) {
                throw MiniError("MINI_ROOT=$root does not contain stdlib/ and runtime/", "point MINI_ROOT at a Mini checkout")
            }
            val explicit = p.env("MINIC")?.takeIf { it.isNotEmpty() }
            val inTree = Paths.join(root, MINIC_RELATIVE)
            val minic = when {
                explicit != null -> explicit
                p.isFile(inTree) -> inTree
                else -> throw MiniError(
                    "the compiler `minic` is not built (${MINIC_RELATIVE} is missing)",
                    "run `./minic --help` once in the repository (it builds the compiler), or set MINIC to a minic launcher",
                )
            }
            val cc = findCompiler(p) ?: throw MiniError("no C compiler found (tried clang and gcc)", "install one (`sudo apt install clang` or `xcode-select --install`), then run `mini doctor`")
            return Toolchain(root, minic, cc.first, cc.first)
        }
    }

    /** Runs minic with the repository root as working directory (so it finds the stdlib and runtime on its own, too). */
    fun runMinic(p: Platform, args: List<String>): ExecResult =
        p.exec(Exec(listOf(minic) + args, cwd = root, env = mapOf("MINI_ROOT" to root)))

    /** A cheap stamp of the compiler installation, so that a new minic rebuilds everything. */
    fun stamp(p: Platform): String {
        val h = Hasher()
        val installDir = Paths.parent(Paths.parent(minic) ?: minic) ?: minic
        for (n in p.list(Paths.join(installDir, "lib"))) {
            val s = p.stat(Paths.join(installDir, "lib", n))
            h.add(n).add(s?.size ?: -1).add(s?.modifiedMillis ?: -1)
        }
        for (dir in listOf(stdlib, runtimeSrc, runtimeInclude)) stampTree(p, dir, h)
        h.add(cc)
        return h.hex()
    }

    private fun stampTree(p: Platform, dir: String, h: Hasher) {
        for (n in p.list(dir)) {
            val f = Paths.join(dir, n)
            if (p.isDirectory(f)) stampTree(p, f, h) else {
                val s = p.stat(f)
                h.add(f).add(s?.size ?: -1).add(s?.modifiedMillis ?: -1)
            }
        }
    }
}
