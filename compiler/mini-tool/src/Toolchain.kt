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
/** Where the wasm32 build finds its tools (see [Toolchain.wasmTools]). */
class WasmTools(val clang: List<String>, val ld: List<String>, val sysroot: String, val runtime: String)

class Toolchain(
    val root: String,
    val minic: String,
    val cc: String,
    val ccName: String,
    val defaultBackend: String = "c",
) {
    val stdlib: String get() = Paths.join(root, "stdlib")
    val include: String get() = Paths.join(root, "include")
    val runtimeLib: String get() = Paths.join(root, "lib/libminirt.a")
    /** The precompiled standard library (docs/mlib.md); packages are built against it unless the installation has none. */
    val stdlibMlib: String get() = Paths.join(root, "lib/stdlib.mlib")

    /** Is the standard library precompiled (and not switched off with `MINI_STDLIB=source`)? Then dependencies are libraries too. */
    fun usesMlib(p: Platform): Boolean = p.env("MINI_STDLIB") != "source" && p.isFile(stdlibMlib)

    /** The compiler id libraries are tagged with: the version of the installation. */
    fun compilerId(p: Platform): String = Mini.version(p)

    /** The standard library part of a minic-native command line: its `.mlib` plus [libs], or (without one) its sources. */
    private fun stdlibArgs(p: Platform, libs: List<String>, declarationOnly: List<String>): List<String> {
        val out = ArrayList<String>()
        if (usesMlib(p)) {
            out += "--lib"; out += stdlibMlib
            for (l in libs) { out += "--lib"; out += l }
            out += "--compiler-id"; out += compilerId(p)
            if (declarationOnly.isNotEmpty()) { out += "--stdlib"; out += declarationOnly }
        } else {
            out += "--stdlib"
            out += stdlibFiles(p)
            out += declarationOnly
        }
        return out
    }

    /** Builds the library [name] from [sources] against [libs] (the `.mlib` files it needs, dependencies first) into [out]. */
    fun emitLib(p: Platform, sources: List<String>, libs: List<String>, out: String, name: String, version: String, werror: Boolean): ExecResult {
        val args = ArrayList<String>()
        args += listOf("--emit-mlib", out, "--name", name, "--lib-version", version, "--compiler-id", compilerId(p))
        for (l in listOf(stdlibMlib) + libs) { args += "--lib"; args += l }
        if (werror) args += "-Werror"
        args += sources
        return runMinic(p, args)
    }

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

        fun locate(p: Platform, requireCompiler: Boolean = true): Toolchain {
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
            val cc = if (requireCompiler) findCompiler(p) ?: throw MiniError("no C compiler found (tried clang and gcc)", "install one (`sudo apt install clang` or `xcode-select --install`), then run `mini doctor`") else null
            val defaultBackend = if (explicit == null && p.readText(Paths.join(root, "TOOLCHAIN-BACKEND"))?.trim() == "llvm-api") "llvm-api" else "c"
            return Toolchain(root, minic, cc?.first ?: "", cc?.first ?: "", defaultBackend)
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
     * other packages' APIs), [cSources] are compiled together with the generated C. The result merges the output of both steps; a non-zero exit code names the failing one.
     */
    fun compile(p: Platform, sources: List<String>, declarationOnly: List<String>, out: String, werror: Boolean, release: Boolean, cSources: List<String> = emptyList(), libs: List<String> = emptyList(), backend: String = "c"): ExecResult {
        if (backend == "llvm-api") return compileLlvmApi(p, sources, declarationOnly, out, werror, release, cSources, libs)
        val cFile = "$out.c"
        val args = ArrayList<String>()
        args += listOf("--backend=c", "-o", cFile)
        if (werror) args += "-Werror"
        args += sources
        args += stdlibArgs(p, libs, declarationOnly)
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
        // C files of the packages (`c-sources`): their directories are include paths too, so `@file:CInclude("x.h")` finds the header
        for (cs in cSources) {
            val d = Paths.parent(cs) ?: continue
            if (d !in dirs) { cmd += "-I"; cmd += d; dirs += d }
        }
        cmd += listOf(cFile)
        cmd += cSources
        cmd += listOf(runtimeLib, "-o", out, "-lm")
        cmd += links
        val cr = p.exec(Exec(cmd))
        p.remove(cFile)
        if (cr.exitCode != 0 || cr.startError != null) {
            val msg = "minic: the C compiler failed (exit ${cr.exitCode}): ${cmd.joinToString(" ")}\n"
            return ExecResult(if (cr.exitCode == 0) 1 else cr.exitCode, r.stdout + cr.stdout, r.stderr + msg + cr.stderr, r.millis + cr.millis, cr.startError)
        }
        return ExecResult(0, r.stdout + cr.stdout, r.stderr + cr.stderr, r.millis + cr.millis)
    }

    /** Builds directly to a native object and links it with the system compiler, without writing generated C. */
    private fun compileLlvmApi(p: Platform, sources: List<String>, declarationOnly: List<String>, out: String, werror: Boolean, release: Boolean, cSources: List<String>, libs: List<String>): ExecResult {
        val args = ArrayList<String>()
        args += listOf("--backend=llvm-api", "--opt=" + if (release) "O2" else "O0", "--exe", out, "--cc", cc)
        if (werror) args += "-Werror"
        args += "--link-arg"; args += runtimeLib
        val includeDirs = ArrayList<String>()
        includeDirs += include
        for (s in cSources) Paths.parent(s)?.let { if (it !in includeDirs) includeDirs += it }
        for (d in includeDirs) { args += "--link-arg"; args += "-I$d" }
        for (source in cSources) { args += "--link-arg"; args += source }
        args += sources
        args += stdlibArgs(p, libs, declarationOnly)
        return runMinic(p, args)
    }

    /** Starts the interactive compiler REPL with all three standard streams inherited from `mini`. */
    fun repl(p: Platform): ExecResult {
        if (defaultBackend != "llvm-api") throw MiniError("`mini repl` needs an LLVM API compiler", "install an LLVM API release or build a toolchain with `scripts/build-mini.sh --backend llvm-api`")
        val args = arrayListOf("--backend=llvm-api", "--repl")
        args += stdlibArgs(p, emptyList(), emptyList())
        return p.exec(Exec(listOf(minic) + args, cwd = root, capture = false))
    }

    /**
     * WebAssembly library: [sources] to `<outDir>/<name>.wasm` + `.mjs` + `.d.ts`. minic writes wasm32 IR (and the glue), [WasmTools.clang]
     * compiles it, [WasmTools.ld] links it with the wasm32 runtime and wasi-libc as a reactor (no `_start`).
     */
    fun compileWasmLib(p: Platform, t: WasmTools, sources: List<String>, libs: List<String>, name: String, outDir: String, werror: Boolean, release: Boolean): ExecResult {
        val ll = Paths.join(outDir, ".$name.ll")
        val obj = Paths.join(outDir, ".$name.o")
        val wasm = Paths.join(outDir, "$name.wasm")
        val args = ArrayList<String>()
        args += listOf("--target=wasm32", "--emit=wasm-lib", "--emit-ll", ll, "--emit-js", Paths.join(outDir, "$name.mjs"), "--emit-dts", Paths.join(outDir, "$name.d.ts"), "--wasm-file", "$name.wasm")
        if (werror) args += "-Werror"
        args += sources
        args += stdlibArgs(p, libs, emptyList())
        val r = runMinic(p, args)
        if (r.exitCode != 0 || r.startError != null) return r
        fun fail(what: String, cmd: List<String>, x: ExecResult): ExecResult {
            val msg = "minic: $what failed (exit ${x.exitCode}): ${cmd.joinToString(" ")}\n"
            return ExecResult(if (x.exitCode == 0) 1 else x.exitCode, r.stdout + x.stdout, r.stderr + msg + x.stderr, r.millis + x.millis, x.startError)
        }
        val cc = t.clang + listOf("--target=wasm32-wasip1", if (release) "-O2" else "-O1", "-Wno-override-module", "-c", ll, "-o", obj)
        val cr = p.exec(Exec(cc))
        p.remove(ll)
        if (cr.exitCode != 0 || cr.startError != null) return fail("the wasm32 C compiler", cc, cr)
        val libDir = Paths.join(t.sysroot, "lib/wasm32-wasip1")
        val ld = t.ld + listOf("-m", "wasm32", "--no-entry", "-z", "stack-size=1048576", "-L$libDir", Paths.join(libDir, "crt1-reactor.o"), obj, t.runtime, "-lc",
            Paths.join(t.sysroot, "lib/wasm32-unknown-wasip1/libclang_rt.builtins.a"), "-o", wasm) + (if (release) listOf("--strip-all") else emptyList())
        val lr = p.exec(Exec(ld))
        p.remove(obj)
        if (lr.exitCode != 0 || lr.startError != null) return fail("wasm-ld", ld, lr)
        return ExecResult(0, r.stdout + cr.stdout + lr.stdout, r.stderr + cr.stderr + lr.stderr, r.millis + cr.millis + lr.millis)
    }

    /**
     * The tools of the wasm32 build: `$MINI_WASM_CLANG` (a command, default `clang`; it needs the wasm32 target), `$MINI_WASM_LD` (default `wasm-ld`),
     * `$MINI_WASM_SYSROOT` (default `<root>/lib/wasm32/sysroot`: wasi-libc, crt1-reactor.o and compiler-rt) and `$MINI_WASM_RUNTIME`
     * (default `<root>/lib/wasm32/libminirt.a`). `scripts/wasm/install-wasm32.sh` stages the last two in a source checkout.
     */
    fun wasmTools(p: Platform): WasmTools {
        fun cmd(name: String, default: String): List<String> = (p.env(name)?.takeIf { it.isNotEmpty() } ?: default).split(' ').filter { it.isNotEmpty() }
        val clang = cmd("MINI_WASM_CLANG", "clang")
        val ld = cmd("MINI_WASM_LD", "wasm-ld")
        val sysroot = p.env("MINI_WASM_SYSROOT")?.takeIf { it.isNotEmpty() } ?: Paths.join(root, "lib/wasm32/sysroot")
        val runtime = p.env("MINI_WASM_RUNTIME")?.takeIf { it.isNotEmpty() } ?: Paths.join(root, "lib/wasm32/libminirt.a")
        val hint = "run `scripts/wasm/install-wasm32.sh` in a source checkout (it needs node and npm), or see `mini doctor`"
        val targets = p.exec(Exec(clang + listOf("--print-targets")))
        if (targets.startError != null || targets.exitCode != 0) throw MiniError("no wasm32 compiler: `${clang.joinToString(" ")}` could not be started", "install clang with the wasm32 target, or set MINI_WASM_CLANG")
        if (!targets.stdout.contains("wasm32")) throw MiniError("`${clang.joinToString(" ")}` has no wasm32 target", "install an LLVM build with WebAssembly support, or set MINI_WASM_CLANG")
        val ldv = p.exec(Exec(ld + listOf("--version")))
        if (ldv.startError != null || ldv.exitCode != 0) throw MiniError("no wasm linker: `${ld.joinToString(" ")}` could not be started", "install lld (wasm-ld), or set MINI_WASM_LD")
        if (!p.isFile(Paths.join(sysroot, "lib/wasm32-wasip1/crt1-reactor.o"))) throw MiniError("no wasm32 sysroot at $sysroot (wasi-libc)", hint)
        if (!p.isFile(runtime)) throw MiniError("the wasm32 runtime is missing ($runtime)", hint)
        return WasmTools(clang, ld, sysroot, runtime)
    }

    /** The frontend and the memory checker for [files] (all of them are checked); [declarationOnly] files only provide declarations. */
    fun check(p: Platform, files: List<String>, declarationOnly: List<String>, werror: Boolean, libs: List<String> = emptyList()): ExecResult {
        val args = ArrayList<String>()
        args += "--check"
        if (werror) args += "-Werror"
        args += files
        args += stdlibArgs(p, libs, declarationOnly)
        return runMinic(p, args)
    }

    /** A cheap stamp of the compiler installation, so that a new compiler or runtime rebuilds everything. */
    fun stamp(p: Platform): String {
        val h = Hasher()
        for (f in listOf(minic, runtimeLib, Paths.join(include, "minirt.h"), stdlibMlib)) {
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
