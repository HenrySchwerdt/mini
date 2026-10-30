package mini.tool

import mini.frontend.FunDecl
import mini.frontend.UserType
import mini.frontend.parseKotlin

class BuildOptions(
    val release: Boolean = false,
    val strict: Boolean = false,
    val werror: Boolean = false,
    val json: Boolean = false,
) {
    val profile: String get() = if (release) "release" else "debug"
}

/** A Kotlin file that goes into a compilation. [path] is what minic gets (the overlay copy for implicit Strict), [real] the user's file. */
class SourceFile(
    val path: String,
    val real: String,
    val display: String,
    val pkg: Package,
    val strict: Boolean,
    val hasMain: Boolean,
    val text: String,
)

class BuildResult(val success: Boolean, val binary: String?, val fresh: Boolean = false, val millis: Long = 0)

/** Prepares sources, decides whether a rebuild is needed and runs minic. */
class Builder(val p: Platform, val c: Console, val tc: Toolchain, val project: Project, val o: BuildOptions) {

    /** Dependencies are precompiled to `.mlib` files (docs/mlib.md) when the installation has a precompiled standard library. */
    val useLibs: Boolean = tc.usesMlib(p)

    // ---- sources ---------------------------------------------------------------------------------------------------

    private class Info(val hasMain: Boolean, val annotated: Boolean, val annotatedStrict: Boolean)

    private fun inspect(text: String): Info {
        val r = parseKotlin(text)
        var annotated = false
        var strict = false
        for (a in r.file.fileAnnotations) {
            val t = a.type
            if (t is UserType && t.segments.lastOrNull()?.name == "MemoryMode") {
                annotated = true
                if (text.substring(a.start, minOf(a.end, text.length)).contains("Strict")) strict = true
            }
        }
        val hasMain = r.file.declarations.any { it is FunDecl && it.name == "main" && it.receiver == null }
        return Info(hasMain, annotated, strict)
    }

    private fun effectiveStrict(pkg: Package): Boolean =
        pkg.info.memory == MemoryDefault.Strict || (o.strict && pkg.origin is Origin.Local)

    fun display(path: String): String {
        val rel = Paths.relativize(project.root, path)
        return if (rel.startsWith("../../..")) path else rel
    }

    private fun gatherFiles(pkg: Package, files: List<String>, overlayRoot: String): List<SourceFile> {
        val out = ArrayList<SourceFile>()
        for (f in files) {
            val text = p.readText(f) ?: continue
            val info = inspect(text)
            val strict = if (info.annotated) info.annotatedStrict else effectiveStrict(pkg)
            var path = f
            if (!info.annotated && strict) {
                path = Paths.join(overlayRoot, pkg.name, Paths.relativize(pkg.dir, f))
                // the annotation goes on the first line so that line numbers stay the same
                p.writeText(path, "@file:mini.memory.MemoryMode(mini.memory.Mode.Strict) $text")
            }
            out += SourceFile(path, f, display(f), pkg, strict, info.hasMain, text)
        }
        return out
    }

    /**
     * All sources of [root] and its dependencies, strict files first (minic's build-time checker looks at the first file).
     * With [tests], the files of tests/ are included and the file that declares `main` is left out (the test runner has its own).
     */
    fun gather(root: Package, tests: Boolean): List<SourceFile> {
        val overlay = Paths.join(project.buildDir, "overlay")
        val all = ArrayList<SourceFile>()
        // with precompiled libraries only the package itself is compiled with the program; its dependencies come as .mlib files
        for (pkg in if (useLibs) listOf(root) else root.closure()) {
            var files = pkg.sources(p)
            if (pkg === root && tests) files = files + pkg.testSources(p)
            val got = gatherFiles(pkg, files, overlay)
            all += if (pkg === root && tests) got.filter { !(it.hasMain && it.real.startsWith(pkg.sourceDir() + "/")) } else got
        }
        return all.sortedBy { if (it.strict) 0 else 1 } // stable
    }

    /** Path rewriting for compiler output: absolute paths become project-relative ones. */
    fun mappings(root: Package): List<Pair<String, String>> {
        val m = ArrayList<Pair<String, String>>()
        for (pkg in root.closure()) {
            val shown = when (val org = pkg.origin) {
                is Origin.Git -> pkg.name + "@" + org.commit.take(7)
                else -> Paths.relativize(project.root, pkg.dir).let { if (it == ".") "" else it }
            }
            m += Pair(pkg.dir + "/", if (shown.isEmpty()) "" else "$shown/")
            m += Pair(Paths.join(project.buildDir, "overlay", pkg.name) + "/", if (shown.isEmpty()) "" else "$shown/")
        }
        return m
    }

    // ---- fingerprint -------------------------------------------------------------------------------------------------

    fun fingerprint(root: Package, files: List<SourceFile>, extra: String, libs: List<LibPlan> = emptyList()): String {
        val h = Hasher()
        for (l in libs) h.add(l.path)
        h.add(Mini.version(p)).add(tc.stamp(p)).add(o.profile).add(o.strict.toString()).add(o.werror.toString()).add(extra)
        for (pkg in root.closure()) {
            h.add(pkg.name).add(pkg.manifest.source)
            for (cs in pkg.cSources()) h.add(cs).add(p.readText(cs) ?: "")
        }
        for (f in files.sortedBy { it.real }) h.add(f.real).add(f.strict.toString()).add(f.text)
        return h.hex()
    }

    // ---- precompiled dependencies --------------------------------------------------------------------------------------

    /** A package built to a library: its `.mlib` is content-addressed (the name holds the fingerprint of everything it was built from). */
    class LibPlan(val pkg: Package, val path: String, val files: List<SourceFile>, val deps: List<String>)

    /**
     * The libraries [root] needs, dependencies before dependents (with [includeRoot] its own library last), without building anything.
     * A library's file name holds a fingerprint of its sources, the libraries it needs and the toolchain, so a library is rebuilt
     * exactly when one of them changes.
     */
    fun planLibs(root: Package, includeRoot: Boolean = false): List<LibPlan> {
        val overlay = Paths.join(project.buildDir, "overlay")
        val plans = LinkedHashMap<String, LibPlan>()
        for (pkg in root.closure()) {
            if (pkg === root && !includeRoot) continue
            val files = gatherFiles(pkg, pkg.sources(p), overlay)
            val deps = pkg.closure().filter { it !== pkg }.map { plans[it.dir]!!.path }
            val h = Hasher()
            h.add(Mini.version(p)).add(tc.stamp(p)).add(o.werror.toString()).add(pkg.name).add(pkg.info.version)
            for (d in deps) h.add(d)
            for (f in files.sortedBy { Paths.relativize(pkg.dir, it.real) }) h.add(Paths.relativize(pkg.dir, f.real)).add(f.strict.toString()).add(f.text)
            val path = Paths.join(project.buildDir, "mlib", pkg.name + "-" + h.hex() + ".mlib")
            plans[pkg.dir] = LibPlan(pkg, path, files, deps)
        }
        return plans.values.toList()
    }

    /** Builds the libraries of [plans] that are not built yet; false when one fails (the diagnostics are printed). */
    fun ensureLibs(root: Package, plans: List<LibPlan>): Boolean {
        for (l in plans) {
            if (p.isFile(l.path)) continue
            val dir = Paths.parent(l.path)!!
            p.mkdirs(dir)
            // older builds of this library are of no use any more
            for (n in p.list(dir)) if (n.startsWith(l.pkg.name + "-") && n.endsWith(".mlib")) p.remove(Paths.join(dir, n))
            announceOne(l.pkg, l.pkg === root)
            c.trace("minic-native --emit-mlib " + Paths.name(l.path))
            val r = tc.emitLib(p, l.files.map { it.path }, l.deps, l.path, l.pkg.name, l.pkg.info.version, o.werror)
            if (r.exitCode != 0) {
                failed(root, r, l.files, "compile")
                return false
            }
            report(root, r, l.files)
        }
        return true
    }

    private fun cSourcesOf(root: Package): List<String> = root.closure().flatMap { it.cSources() }

    // ---- compiling ---------------------------------------------------------------------------------------------------

    private val nameOnlyDiag = KotlinDiagPattern(allowSlash = false)

    private fun report(root: Package, r: ExecResult, files: List<SourceFile>): Boolean {
        val maps = mappings(root)
        var text = Diagnostics.remap(Diagnostics.clean(r.stdout + (if (r.stdout.isNotEmpty() && r.stderr.isNotEmpty()) "\n" else "") + r.stderr), maps)
        // the Kotlin frontend prints only the file *name*: use the project path when it is unambiguous
        val byName = files.groupBy { Paths.name(it.real) }
        text = text.lines().joinToString("\n") { l ->
            val m = nameOnlyDiag.find(l)
            val cand = m?.let { byName[it.groupValues[1]] }
            if (m != null && cand != null && cand.size == 1) cand[0].display + l.substring(m.groupValues[1].length) else l
        }
        if (text.isBlank()) return true
        if (o.json) {
            for (d in Diagnostics.parse(text)) c.println(Diagnostics.toJson(d, root.name))
        } else {
            c.eprintln(Diagnostics.colorize(text, c.err))
        }
        return true
    }

    private fun failed(root: Package, r: ExecResult, files: List<SourceFile>, what: String): BuildResult {
        report(root, r, files)
        if (r.startError != null) throw MiniError("could not start minic-native (${tc.minic}): ${r.startError}", "run `mini doctor`")
        if (!o.json) c.error("could not $what `${root.name}` due to the errors above")
        return BuildResult(false, null)
    }

    /**
     * The memory checker only looks at the first file of a `minic` invocation, so every further Strict file gets its own
     * `minic check` run (all other files as declaration-only context). Returns false when one of them failed.
     */
    private fun checkEachStrict(root: Package, files: List<SourceFile>, what: String, libs: List<LibPlan>): Boolean {
        val strict = files.filter { it.strict }
        for (f in strict.drop(1)) {
            c.status("Checking", display(f.real))
            c.trace("minic-native --check " + f.path)
            val r = tc.check(p, listOf(f.path), files.filter { it !== f }.map { it.path }, o.werror, libs.map { it.path })
            if (r.exitCode != 0) { failed(root, r, files, what); return false }
            report(root, r, files)
        }
        return true
    }

    private fun announce(root: Package) {
        for (pkg in if (useLibs) listOf(root) else root.closure()) announceOne(pkg, pkg === root)
    }

    private fun announceOne(pkg: Package, isRoot: Boolean) {
        val where = when (val org = pkg.origin) {
            is Origin.Git -> "(git ${org.url}#${org.commit.take(7)})"
            else -> "(" + display(pkg.dir).let { if (it == ".") "." else it } + ")"
        }
        c.status("Compiling", "${pkg.id} $where" + if (pkg.info.kind == Kind.Lib && isRoot) " [lib]" else "")
    }

    private fun fresh(fpFile: String, fp: String, binary: String?): Boolean =
        p.readText(fpFile)?.trim() == fp && (binary == null || p.isFile(binary))

    /** `mini build` for one package: apps become `build/<profile>/<name>`, libraries become a `.mlib` in `build/mlib/`. */
    fun build(root: Package): BuildResult {
        val files = gather(root, tests = false)
        if (root.sources(p).isEmpty()) {
            throw MiniError("package `${root.name}` has no Kotlin sources", "put code in ${display(root.sourceDir())}/" + if (root.info.kind == Kind.App) "main.kt (with `fun main()`)" else "lib.kt")
        }
        val isApp = root.info.kind == Kind.App
        if (useLibs && !isApp) return buildLib(root)
        val libs = if (useLibs) planLibs(root) else emptyList()
        val outDir = Paths.join(project.buildDir, o.profile)
        val binary = if (isApp) Paths.join(outDir, root.name) else Paths.join(outDir, ".lib", root.name + ".check")
        val fpFile = Paths.join(outDir, ".fingerprint", root.name)
        val fp = fingerprint(root, files, "build", libs)
        val start = p.nowMillis()
        if (fresh(fpFile, fp, binary)) {
            c.status("Fresh", "${root.id} (no changes)")
            return BuildResult(true, if (isApp) binary else null, fresh = true)
        }
        if (isApp && files.count { it.hasMain } == 0) {
            throw MiniError("no `fun main()` in ${display(root.sourceDir())}/", "add `fun main() { println(\"Hello\") }` to src/main.kt, or set `kind = \"lib\"` in mini.toml")
        }
        if (!ensureLibs(root, libs)) return BuildResult(false, null)
        announce(root)
        p.mkdirs(Paths.parent(binary)!!)
        val sources = ArrayList<String>(files.map { it.path })
        if (!isApp) {
            val stub = Paths.join(outDir, ".lib", root.name + "_main.kt")
            p.writeText(stub, "// generated by mini: a library has no main, this stub lets minic compile it end to end\nfun main() {}\n")
            sources += stub
        }
        if (!checkEachStrict(root, files, "compile", libs)) return BuildResult(false, null)
        c.trace("minic-native " + sources.joinToString(" ") + " -> " + binary)
        val r = tc.compile(p, sources, emptyList(), binary, o.werror, o.release, cSourcesOf(root), libs.map { it.path })
        if (r.exitCode != 0) return failed(root, r, files, "compile")
        report(root, r, files)
        p.writeText(fpFile, fp + "\n")
        val ms = p.nowMillis() - start
        return BuildResult(true, if (isApp) binary else null, millis = ms)
    }

    /** A library package: its dependencies and itself are built to `.mlib` files (nothing is linked, no C compiler is needed). */
    private fun buildLib(root: Package): BuildResult {
        val plans = planLibs(root, includeRoot = true)
        val start = p.nowMillis()
        if (plans.all { p.isFile(it.path) }) {
            c.status("Fresh", "${root.id} (no changes)")
            return BuildResult(true, null, fresh = true)
        }
        if (!ensureLibs(root, plans)) return BuildResult(false, null)
        return BuildResult(true, null, millis = p.nowMillis() - start)
    }

    /** The `.mlib` of a library package, built when it is not yet (null when it fails); the file `mini package` distributes. */
    fun libraryFile(root: Package): String? {
        val plans = planLibs(root, includeRoot = true)
        if (!ensureLibs(root, plans)) return null
        return plans.last().path
    }

    /** `mini check` for one package: frontend diagnostics and the memory checker, no C compiler. */
    fun check(root: Package): BuildResult {
        val files = gather(root, tests = false)
        if (files.isEmpty()) throw MiniError("package `${root.name}` has no Kotlin sources", "put code in ${display(root.sourceDir())}/")
        val start = p.nowMillis()
        c.status("Checking", "${root.id} (${display(root.dir).let { if (it == ".") "." else it }})")
        // every source file is a user file of one run: all of them are type-checked and (Strict ones) memory-checked
        val libs = if (useLibs) planLibs(root) else emptyList()
        if (!ensureLibs(root, libs)) return BuildResult(false, null)
        c.trace("minic-native --check " + files.joinToString(" ") { it.path })
        val r = tc.check(p, files.map { it.path }, emptyList(), o.werror, libs.map { it.path })
        val ok = r.exitCode == 0
        if (!ok) failed(root, r, files, "check") else report(root, r, files)
        return BuildResult(ok, null, millis = p.nowMillis() - start)
    }

    /** Compiles an already prepared set of files into [binary] (used by `mini test`). */
    fun compileSet(root: Package, files: List<SourceFile>, extraSources: List<String>, declarationOnly: List<String>, binary: String, fpFile: String, kindKey: String): BuildResult {
        val libs = if (useLibs) planLibs(root) else emptyList()
        val fp = fingerprint(root, files, kindKey + (extraSources + declarationOnly).joinToString { p.readText(it) ?: "" }, libs)
        val start = p.nowMillis()
        if (fresh(fpFile, fp, binary)) return BuildResult(true, binary, fresh = true)
        p.mkdirs(Paths.parent(binary)!!)
        if (!ensureLibs(root, libs)) return BuildResult(false, null)
        if (!checkEachStrict(root, files, "build the tests of", libs)) return BuildResult(false, null)
        c.trace("minic-native " + files.joinToString(" ") { it.path } + " -> " + binary)
        val r = tc.compile(p, files.map { it.path } + extraSources, declarationOnly, binary, o.werror, o.release, cSourcesOf(root), libs.map { it.path })
        if (r.exitCode != 0) return failed(root, r, files, "build the tests of")
        report(root, r, files)
        p.writeText(fpFile, fp + "\n")
        return BuildResult(true, binary, millis = p.nowMillis() - start)
    }
}
