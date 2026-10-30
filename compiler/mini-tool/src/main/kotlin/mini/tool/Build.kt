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
        for (pkg in root.closure()) {
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

    fun fingerprint(root: Package, files: List<SourceFile>, extra: String): String {
        val h = Hasher()
        h.add(Mini.VERSION).add(tc.stamp(p)).add(o.profile).add(o.strict.toString()).add(o.werror.toString()).add(extra)
        for (pkg in root.closure()) {
            h.add(pkg.name).add(pkg.manifest.source)
        }
        for (f in files.sortedBy { it.real }) h.add(f.real).add(f.strict.toString()).add(f.text)
        return h.hex()
    }

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
    private fun checkEachStrict(root: Package, files: List<SourceFile>, what: String): Boolean {
        val strict = files.filter { it.strict }
        for (f in strict.drop(1)) {
            c.status("Checking", display(f.real))
            c.trace("minic-native --check " + f.path)
            val r = tc.check(p, f.path, files.filter { it !== f }.map { it.path }, o.werror)
            if (r.exitCode != 0) { failed(root, r, files, what); return false }
            report(root, r, files)
        }
        return true
    }

    private fun announce(root: Package) {
        for (pkg in root.closure()) {
            val where = when (val org = pkg.origin) {
                is Origin.Git -> "(git ${org.url}#${org.commit.take(7)})"
                else -> "(" + display(pkg.dir).let { if (it == ".") "." else it } + ")"
            }
            c.status("Compiling", "${pkg.id} $where" + if (pkg.info.kind == Kind.Lib && pkg === root) " [lib]" else "")
        }
    }

    private fun fresh(fpFile: String, fp: String, binary: String?): Boolean =
        p.readText(fpFile)?.trim() == fp && (binary == null || p.isFile(binary))

    /** `mini build` for one package: apps become `build/<profile>/<name>`, libraries are compiled once to verify them. */
    fun build(root: Package): BuildResult {
        val files = gather(root, tests = false)
        if (root.sources(p).isEmpty()) {
            throw MiniError("package `${root.name}` has no Kotlin sources", "put code in ${display(root.sourceDir())}/" + if (root.info.kind == Kind.App) "main.kt (with `fun main()`)" else "lib.kt")
        }
        val isApp = root.info.kind == Kind.App
        val outDir = Paths.join(project.buildDir, o.profile)
        val binary = if (isApp) Paths.join(outDir, root.name) else Paths.join(outDir, ".lib", root.name + ".check")
        val fpFile = Paths.join(outDir, ".fingerprint", root.name)
        val fp = fingerprint(root, files, "build")
        val start = p.nowMillis()
        if (fresh(fpFile, fp, binary)) {
            c.status("Fresh", "${root.id} (no changes)")
            return BuildResult(true, if (isApp) binary else null, fresh = true)
        }
        if (isApp && files.count { it.hasMain } == 0) {
            throw MiniError("no `fun main()` in ${display(root.sourceDir())}/", "add `fun main() { println(\"Hello\") }` to src/main.kt, or set `kind = \"lib\"` in mini.toml")
        }
        announce(root)
        p.mkdirs(Paths.parent(binary)!!)
        val sources = ArrayList<String>(files.map { it.path })
        if (!isApp) {
            val stub = Paths.join(outDir, ".lib", root.name + "_main.kt")
            p.writeText(stub, "// generated by mini: a library has no main, this stub lets minic compile it end to end\nfun main() {}\n")
            sources += stub
        }
        if (!checkEachStrict(root, files, "compile")) return BuildResult(false, null)
        c.trace("minic-native " + sources.joinToString(" ") + " -> " + binary)
        val r = tc.compile(p, sources, emptyList(), binary, o.werror, o.release)
        if (r.exitCode != 0) return failed(root, r, files, "compile")
        report(root, r, files)
        p.writeText(fpFile, fp + "\n")
        val ms = p.nowMillis() - start
        return BuildResult(true, if (isApp) binary else null, millis = ms)
    }

    /** `mini check` for one package: frontend diagnostics and the memory checker, no C compiler. */
    fun check(root: Package): BuildResult {
        val files = gather(root, tests = false)
        if (files.isEmpty()) throw MiniError("package `${root.name}` has no Kotlin sources", "put code in ${display(root.sourceDir())}/")
        val start = p.nowMillis()
        c.status("Checking", "${root.id} (${display(root.dir).let { if (it == ".") "." else it }})")
        val targets = files.filter { it.strict }.ifEmpty { listOf(files.first()) }
        var ok = true
        for (f in targets) {
            c.trace("minic-native --check " + f.path)
            val r = tc.check(p, f.path, files.filter { it !== f }.map { it.path }, o.werror)
            if (r.exitCode != 0) { failed(root, r, files, "check"); ok = false; break }
            report(root, r, files)
        }
        return BuildResult(ok, null, millis = p.nowMillis() - start)
    }

    /** Compiles an already prepared set of files into [binary] (used by `mini test`). */
    fun compileSet(root: Package, files: List<SourceFile>, extraSources: List<String>, declarationOnly: List<String>, binary: String, fpFile: String, kindKey: String): BuildResult {
        val fp = fingerprint(root, files, kindKey + (extraSources + declarationOnly).joinToString { p.readText(it) ?: "" })
        val start = p.nowMillis()
        if (fresh(fpFile, fp, binary)) return BuildResult(true, binary, fresh = true)
        p.mkdirs(Paths.parent(binary)!!)
        if (!checkEachStrict(root, files, "build the tests of")) return BuildResult(false, null)
        c.trace("minic-native " + files.joinToString(" ") { it.path } + " -> " + binary)
        val r = tc.compile(p, files.map { it.path } + extraSources, declarationOnly, binary, o.werror, o.release)
        if (r.exitCode != 0) return failed(root, r, files, "build the tests of")
        report(root, r, files)
        p.writeText(fpFile, fp + "\n")
        return BuildResult(true, binary, millis = p.nowMillis() - start)
    }
}
