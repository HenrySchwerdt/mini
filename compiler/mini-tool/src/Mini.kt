package mini.tool

/** Entry point of the core: `Mini.run(args, platform)` returns the process exit code. */
object Mini {
    /** Shown when the installation has no `VERSION` file (a bare compiler binary). */
    const val UNKNOWN_VERSION = "unknown"

    /** The toolchain version: the `VERSION` file of the installation (a release archive, or `build/native` of a checkout). */
    fun version(p: Platform): String {
        val root = Toolchain.findRoot(p) ?: return UNKNOWN_VERSION
        return p.readText(Paths.join(root, "VERSION"))?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_VERSION
    }

    fun run(args: List<String>, p: Platform): Int {
        // Colour and verbosity must be known before the full parse so that usage errors are coloured too.
        val early = earlyConsole(args, p)
        val parsed = try {
            CommandLine.parse(args)
        } catch (e: UsageError) {
            early.error(e.msg, e.help)
            return 2
        }
        val verbosity = when {
            parsed.has("quiet") || parsed.value("message-format") == "json" -> Verbosity.Quiet
            parsed.has("verbose") -> Verbosity.Verbose
            else -> Verbosity.Normal
        }
        val c = Console(p, colorMode(parsed.value("color")), verbosity)
        return try {
            Commands(p, c, parsed).dispatch()
        } catch (e: UsageError) {
            c.error(e.msg, e.help)
            2
        } catch (e: MiniError) {
            if (e.rendered != null) c.eprint(e.rendered) else c.error(e.msg, e.help)
            if (e.rendered != null && e.help != null) c.eprintln("  " + c.err.cyan("help:", true) + " " + e.help)
            e.exitCode
        } catch (e: ManifestException) {
            c.eprint(e.render(c.err))
            1
        } catch (e: Exception) {
            c.error("internal error in mini: ${e.message ?: e.toString()}", "this is a bug; please report it with the command you ran")
            if (p.env("MINI_DEBUG") != null) c.eprintln(e.toString())
            101
        }
    }

    private fun colorMode(v: String?) = when (v) { "always" -> ColorMode.Always; "never" -> ColorMode.Never; else -> ColorMode.Auto }

    private fun earlyConsole(args: List<String>, p: Platform): Console {
        val i = args.indexOf("--color")
        val v = if (i >= 0 && i + 1 < args.size) args[i + 1] else args.firstOrNull { it.startsWith("--color=") }?.substringAfter('=')
        return Console(p, colorMode(v), Verbosity.Normal)
    }
}

class Commands(val p: Platform, val c: Console, val a: Parsed) {
    private val loader = ProjectLoader(p, c)

    fun dispatch(): Int {
        if (a.has("version")) { c.println("mini ${Mini.version(p)}"); return 0 }
        if (a.has("help") && a.command.name != "help") { c.print(CommandLine.commandHelp(a.command, c.out)); return 0 }
        return when (a.command.name) {
            "help" -> help()
            "new" -> newProject()
            "init" -> initProject()
            "build" -> build()
            "run" -> runApp()
            "check" -> check()
            "package" -> packageLibrary()
            "test" -> test()
            "clean" -> clean()
            "add" -> add()
            "remove" -> remove()
            "update" -> update()
            "tree" -> tree()
            "explain" -> explain()
            "doctor" -> Doctor(p, c).run()
            "repl" -> repl()
            "completions" -> { c.print(Completions.generate(a.positionals[0])); 0 }
            else -> throw UsageError("unknown command `${a.command.name}`")
        }
    }

    // ---- help ------------------------------------------------------------------------------------------------------

    private fun help(): Int {
        if (a.has("help") && a.positionals.isEmpty() || a.positionals.isEmpty()) {
            c.print(CommandLine.topHelp(c.out, Mini.version(p)))
            return 0
        }
        val name = a.positionals[0]
        val cmd = CommandLine.find(name) ?: throw UsageError(
            "no help topic `$name`", suggest(name, CommandLine.commands.map { it.name })?.let { "did you mean `$it`?" } ?: "run `mini help` for the list of commands",
        )
        c.print(CommandLine.commandHelp(cmd, c.out))
        return 0
    }

    // ---- new / init ------------------------------------------------------------------------------------------------

    private fun newProject(): Int {
        val target = a.positionals[0]
        val dir = Paths.resolve(p.cwd(), target)
        val name = Paths.name(dir)
        validateName(name, "mini new ${suggestion(name)}")
        if (p.exists(dir)) {
            throw MiniError("destination `${loader.display(dir)}` already exists", "choose another name, or run `mini init` inside the existing directory")
        }
        val kind = templateKind()
        scaffold(dir, name, kind, a.has("strict"), !a.has("no-git"))
        val shown = loader.display(dir)
        c.status("Created", "${kindWord(kind)} `$name` at $shown/")
        nextSteps(shown, kind)
        return 0
    }

    private fun templateKind(): Kind {
        val t = a.value("template")
        if (t != null && a.has("lib") && t != "lib") throw UsageError("`--lib` and `--template $t` contradict each other", "use only `--template $t`")
        return when (t) {
            null -> if (a.has("lib")) Kind.Lib else Kind.App
            "app" -> Kind.App
            "lib" -> Kind.Lib
            "wasm-lib" -> Kind.WasmLib
            else -> throw UsageError("unknown template `$t`", "templates: app, lib, wasm-lib")
        }
    }

    private fun kindWord(kind: Kind): String = when (kind) { Kind.App -> "app"; Kind.Lib -> "library"; Kind.WasmLib -> "WebAssembly library" }

    private fun suggestion(name: String): String = ManifestParser.sanitizeName(name)

    private fun validateName(name: String, tryCmd: String) {
        if (!ManifestParser.isValidName(name)) {
            throw MiniError(
                "invalid package name `$name`",
                "names start with a lowercase letter and contain only a-z, 0-9, `_` and `-`" + (if (name.isNotEmpty()) "; try `$tryCmd`" else ""),
            )
        }
    }

    private fun initProject(): Int {
        val dir = p.cwd()
        if (p.exists(Paths.join(dir, "mini.toml"))) {
            throw MiniError("`mini.toml` already exists in ${loader.display(dir)}", "this directory is already a Mini project; try `mini build`")
        }
        val name = a.value("name") ?: Paths.name(dir)
        validateName(name, "mini init --name ${suggestion(name)}")
        val kind = templateKind()
        scaffold(dir, name, kind, a.has("strict"), !a.has("no-git"))
        c.status("Initialized", "${kindWord(kind)} `$name` in the current directory")
        nextSteps(null, kind)
        return 0
    }

    private fun scaffold(dir: String, name: String, kind: Kind, strict: Boolean, git: Boolean) {
        val files = LinkedHashMap<String, String>()
        files["mini.toml"] = Templates.manifest(name, kind, strict)
        if (kind == Kind.WasmLib) {
            files["src/lib.kt"] = Templates.wasmLibKt()
            files["tests/lib_test.kt"] = Templates.wasmLibTest()
            files["index.html"] = Templates.wasmLibHtml(name)
            files["run.mjs"] = Templates.wasmLibRunMjs(name)
        } else if (kind == Kind.App) {
            files["src/main.kt"] = Templates.mainKt()
            files["src/greeting.kt"] = Templates.greetingKt()
            files["tests/greeting_test.kt"] = Templates.greetingTest()
        } else {
            val pkg = name.toIdentifier()
            files["src/lib.kt"] = Templates.libKt(pkg)
            files["tests/lib_test.kt"] = Templates.libTest(pkg)
        }
        files[".gitignore"] = Templates.gitignore()
        files["README.md"] = Templates.readme(name, kind)
        p.mkdirs(dir)
        for ((rel, text) in files) {
            val f = Paths.join(dir, rel)
            if (p.exists(f)) { c.statusWarn("Skipped", "$rel (already exists)"); continue }
            p.mkdirs(Paths.parent(f)!!)
            p.writeText(f, text)
            if (c.verbose) c.status("Created", rel)
        }
        if (git) {
            val inRepo = p.exec(Exec(listOf("git", "rev-parse", "--is-inside-work-tree"), cwd = dir))
            if (inRepo.exitCode != 0 && inRepo.startError == null) p.exec(Exec(listOf("git", "init", "--quiet"), cwd = dir))
        }
    }

    private fun nextSteps(dir: String?, kind: Kind) {
        val lib = kind != Kind.App
        if (c.quiet) return
        val s = c.err
        c.eprintln()
        c.eprintln(s.bold("Next steps:"))
        if (dir != null) c.eprintln("  ${s.cyan("cd $dir")}")
        if (!lib) c.eprintln("  ${s.cyan("mini run")}      build and run the app")
        c.eprintln("  ${s.cyan("mini test")}     run the tests in tests/")
        c.eprintln("  ${s.cyan(if (lib) "mini build" else "mini check")}    ${if (kind == Kind.WasmLib) "build build/wasm/<name>.wasm and its JavaScript glue" else if (lib) "compile the library" else "type-check and run the memory checker only"}")
        c.eprintln("  ${s.cyan("mini help")}     all commands")
        if (!lib) c.eprintln("\nEdit ${s.bold("src/main.kt")} and run ${s.bold("mini run")} again.")
    }

    // ---- project commands ------------------------------------------------------------------------------------------

    private fun loadOptions(update: Set<String> = emptySet(), updateAll: Boolean = false, writeLock: Boolean = true) = LoadOptions(
        startDir = p.cwd(),
        manifestPath = a.value("manifest-path"),
        packageNames = a.values("package"),
        offline = a.has("offline"),
        locked = a.has("locked"),
        update = update,
        updateAll = updateAll,
        writeLock = writeLock,
    )

    private fun buildOptions(backendDefault: String): BuildOptions {
        val target = a.value("target")
        if (target == null && a.has("lib") && a.command.name == "build") throw UsageError("`--lib` needs `--target wasm32`", "use `mini build --target wasm32 --lib`")
        return BuildOptions(a.has("release"), a.has("strict"), a.has("Werror"), a.value("message-format") == "json", target == "wasm32" && a.has("lib"), target == "wasm32", a.value("backend") ?: backendDefault)
    }

    private fun builder(project: Project): Builder {
        val tc = Toolchain.locate(p)
        return Builder(p, c, tc, project, buildOptions(tc.defaultBackend))
    }

    private fun repl(): Int {
        if (a.positionals.isNotEmpty()) throw UsageError("`mini repl` takes no arguments")
        val result = Toolchain.locate(p, requireCompiler = false).repl(p)
        if (result.startError != null) throw MiniError("could not start the LLVM API REPL: ${result.startError}")
        return result.exitCode
    }

    private fun build(): Int {
        val project = loader.load(loadOptions())
        val b = builder(project)
        val start = p.nowMillis()
        var ok = true
        var allFresh = true
        for (pkg in project.selected) {
            val r = b.build(pkg)
            if (b.o.json) c.println(Diagnostics.finishedJson(r.success, pkg.name, r.millis))
            if (!r.success) { ok = false; break }
            if (!r.fresh) allFresh = false
        }
        if (!ok) return 1
        if (!allFresh) c.status("Finished", "${b.o.profile} build in ${formatDuration(p.nowMillis() - start)}")
        for (pkg in project.selected) if (pkg.info.kind == Kind.App && !b.o.wasmLib && !c.quiet) {
            c.status("Binary", b.display(Paths.join(project.buildDir, b.o.profile, pkg.name)))
        } else if ((pkg.info.kind == Kind.WasmLib || b.o.wasmLib) && !c.quiet) {
            c.status("Wasm", b.display(Paths.join(project.buildDir, "wasm", pkg.name + ".wasm")) + " (+ .mjs, .d.ts)")
        }
        return 0
    }

    private fun runApp(): Int {
        val project = loader.load(loadOptions())
        val apps = project.selected.filter { it.info.kind == Kind.App }
        if (apps.isEmpty()) {
            throw MiniError(
                "nothing to run: ${project.selected.joinToString { "`${it.name}`" }} ${if (project.selected.size == 1) "is a library" else "are libraries"}",
                "libraries are used from apps; add `kind = \"app\"` and a `fun main()`, or run an app of the workspace with `-p <name>`",
            )
        }
        if (apps.size > 1) {
            throw MiniError("this workspace has several apps: ${apps.joinToString { "`${it.name}`" }}", "choose one with `mini run -p <name>`")
        }
        val pkg = apps.single()
        val b = builder(Project(project.root, project.rootManifest, project.members, listOf(pkg), project.lock, project.isWorkspace))
        val start = p.nowMillis()
        val r = b.build(pkg)
        if (b.o.json) c.println(Diagnostics.finishedJson(r.success, pkg.name, r.millis))
        if (!r.success) return 1
        if (!r.fresh) c.status("Finished", "${b.o.profile} build in ${formatDuration(p.nowMillis() - start)}")
        val bin = r.binary!!
        val shown = Paths.relativize(p.cwd(), bin).let { if (it.startsWith("../..")) bin else it }
        c.status("Running", "`$shown`" + if (a.rest.isNotEmpty()) " " + a.rest.joinToString(" ") else "")
        val res = p.exec(Exec(listOf(bin) + a.rest, cwd = p.cwd(), capture = false))
        if (res.startError != null) throw MiniError("could not start `$shown`: ${res.startError}")
        if (res.exitCode >= 129 && res.exitCode <= 159) {
            val sig = when (res.exitCode - 128) { 6 -> "SIGABRT (panic)"; 11 -> "SIGSEGV"; 8 -> "SIGFPE"; 9 -> "SIGKILL"; 15 -> "SIGTERM"; else -> "signal ${res.exitCode - 128}" }
            c.error("`$shown` was terminated by $sig")
        }
        return res.exitCode
    }

    private fun check(): Int {
        val project = loader.load(loadOptions())
        val b = builder(project)
        val start = p.nowMillis()
        for (pkg in project.selected) {
            val r = b.check(pkg)
            if (b.o.json) c.println(Diagnostics.finishedJson(r.success, pkg.name, r.millis))
            if (!r.success) return 1
        }
        c.status("Finished", "checking in ${formatDuration(p.nowMillis() - start)}, no errors")
        return 0
    }

    private fun packageLibrary(): Int {
        val project = loader.load(loadOptions())
        val b = builder(project)
        if (!b.useLibs) throw MiniError("`mini package` needs a toolchain with a precompiled standard library (lib/stdlib.mlib)", "install a release, or run scripts/build-mini.sh in a source checkout")
        for (pkg in project.selected) {
            if (pkg.info.kind != Kind.Lib) throw MiniError("`${pkg.name}` is ${if (pkg.info.kind == Kind.WasmLib) "a wasm-lib" else "an app"}: only libraries can be packaged", "set `kind = \"lib\"` in its mini.toml")
            val built = b.libraryFile(pkg) ?: return 1
            val out = Paths.join(project.buildDir, "package", "${pkg.name}-${pkg.info.version}.mlib")
            p.mkdirs(Paths.parent(out)!!)
            val text = p.readText(built) ?: throw MiniError("cannot read the built library $built")
            p.writeText(out, text)
            val hash = text.take(600).lines().firstOrNull { it.startsWith("hash ") }?.substring(5) ?: "?"
            c.status("Packaged", "${pkg.id} -> ${b.display(out)} (hash $hash)")
        }
        return 0
    }

    private fun test(): Int {
        val project = loader.load(loadOptions())
        val list = a.has("list")
        val b = if (list) Builder(p, c, Toolchain(project.root, "", "clang", "clang"), project, buildOptions("c")) else builder(project)
        val runner = TestRunner(p, c, b)
        var code = 0
        for (pkg in project.selected) {
            if (project.selected.size > 1 && !list) c.eprintln("\n" + c.err.bold("== ${pkg.id} =="))
            val r = runner.run(pkg, a.positionals.firstOrNull(), list, a.has("nocapture"), a.has("fail-fast"), a.rest)
            if (r != 0) { code = r; if (a.has("fail-fast")) break }
        }
        return code
    }

    private fun clean(): Int {
        val (_, root) = loader.locate(loadOptions())
        val dir = Paths.join(root.dir, "build")
        if (!p.exists(dir)) { c.status("Clean", "nothing to remove (${loader.display(dir)} does not exist)"); return 0 }
        val n = p.remove(dir)
        c.status("Removed", "${loader.display(dir)}/ ($n file${if (n == 1) "" else "s"})")
        return 0
    }

    // ---- dependencies ------------------------------------------------------------------------------------------------

    private fun targetManifest(): Manifest {
        val names = a.values("package")
        if (names.isNotEmpty()) {
            val project = loader.load(loadOptions(writeLock = false))
            val m = project.members.firstOrNull { it.name == names[0] } ?: throw MiniError(
                "no package named `${names[0]}` in this workspace",
                suggest(names[0], project.members.map { it.name })?.let { "did you mean `$it`?" },
            )
            return m.manifest
        }
        val (found, _) = loader.locate(loadOptions())
        if (found.pkg == null) {
            throw MiniError("${loader.display(found.file)} is a workspace without a package of its own", "choose the member with `-p <name>` or run the command inside the member directory")
        }
        return found
    }

    private fun add(): Int {
        val path = a.value("path")
        val git = a.value("git")
        if ((path == null) == (git == null)) {
            throw UsageError("`mini add` needs exactly one of `--path <dir>` or `--git <url>`", "e.g. `mini add geometry --path ../geometry`")
        }
        if (path != null && listOf("tag", "rev", "branch").any { a.has(it) }) throw UsageError("`--tag`, `--rev` and `--branch` only apply to `--git`")
        if (listOf("tag", "rev", "branch").count { a.has(it) } > 1) throw UsageError("use only one of `--tag`, `--rev`, `--branch`")
        val mf = targetManifest()
        var name = a.positionals.firstOrNull()
        val spec: String
        if (path != null) {
            val dir = p.realPath(Paths.resolve(p.cwd(), path))
            if (!p.isFile(Paths.join(dir, "mini.toml"))) throw MiniError("`$path` is not a Mini package (no mini.toml there)", "check the path, or create the library with `mini new <name> --lib`")
            val dm = loader.readManifest(Paths.join(dir, "mini.toml"))
            val depName = dm.pkg?.name ?: throw MiniError("${loader.display(dm.file)} has no `[package]`")
            if (name != null && name != depName) throw MiniError("the package at `$path` is called `$depName`, not `$name`", "run `mini add $depName --path $path`, or omit the name")
            name = depName
            val rel = Paths.relativize(mf.dir, dir)
            spec = "{ path = \"${if (rel.startsWith("..") || rel == ".") rel else rel}\" }"
        } else {
            val refKey = listOf("tag", "rev", "branch").firstOrNull { a.has(it) }
            val refText = if (refKey != null) ", $refKey = \"${a.value(refKey)}\"" else ""
            if (name == null) {
                val ref = when (refKey) { "tag" -> GitRef.Tag(a.value("tag")!!); "rev" -> GitRef.Rev(a.value("rev")!!); "branch" -> GitRef.Branch(a.value("branch")!!); else -> GitRef.Default }
                val cache = GitCache(p, c, miniHome(p), a.has("offline"))
                val url = git!!
                val clone = if (url.startsWith("./") || url.startsWith("../")) Paths.resolve(p.cwd(), url) else url
                val commit = cache.resolve(url, clone, ref, true)
                val dir = cache.checkout(url, clone, commit)
                val dm = loader.readManifest(Paths.join(dir, "mini.toml"))
                name = dm.pkg?.name ?: throw MiniError("the repository `$url` has no `[package]` in its mini.toml")
            }
            spec = "{ git = \"${git!!}\"$refText }"
        }
        val (text, replaced) = ManifestEdit.addDependency(mf.source, name!!, spec)
        p.writeText(mf.file, text)
        try {
            loader.load(LoadOptions(startDir = p.cwd(), manifestPath = mf.file, offline = a.has("offline")))
        } catch (e: MiniError) {
            p.writeText(mf.file, mf.source)
            throw e
        }
        c.status(if (replaced) "Updating" else "Adding", "$name (${if (path != null) "path ${spec.substringAfter("\"").substringBefore("\"")}" else "git ${a.value("git")}"}) to dependencies of `${mf.pkg!!.name}`")
        return 0
    }

    private fun remove(): Int {
        val name = a.positionals[0]
        val mf = targetManifest()
        val text = ManifestEdit.removeDependency(mf.source, name)
        if (text == null) {
            val names = ManifestEdit.dependencyNames(mf.source)
            throw MiniError(
                "`$name` is not a dependency of `${mf.pkg!!.name}`",
                suggest(name, names)?.let { "did you mean `$it`?" } ?: if (names.isEmpty()) "there are no dependencies" else "dependencies: " + names.joinToString(", ") { "`$it`" },
            )
        }
        p.writeText(mf.file, text)
        c.status("Removing", "$name from ${loader.display(mf.file)}")
        try {
            loader.load(LoadOptions(startDir = p.cwd(), manifestPath = mf.file, offline = true))
        } catch (e: MiniError) { /* the lock file is refreshed on the next build */ }
        return 0
    }

    private fun update(): Int {
        val (_, root) = loader.locate(loadOptions())
        val before = Lockfile.parse(p.readText(Paths.join(root.dir, "mini.lock")) ?: "")
        val names = a.positionals.toSet()
        val project = loader.load(loadOptions(update = names, updateAll = names.isEmpty()))
        if (project.lock.git.isEmpty()) { c.status("Nothing", "to update: no git dependencies"); return 0 }
        var changed = 0
        for ((n, e) in project.lock.git) {
            val old = before.git[n]
            if (old == null) { c.status("Locked", "$n -> ${e.commit.take(7)}"); changed++ }
            else if (old.commit != e.commit) { c.status("Updating", "$n ${old.commit.take(7)} -> ${e.commit.take(7)}"); changed++ }
        }
        if (changed == 0) c.status("Up to date", "all git dependencies are at their newest commits")
        for (n in names) if (n !in project.lock.git) c.warn("`$n` is not a git dependency, nothing to update")
        return 0
    }

    private fun tree(): Int {
        val project = loader.load(loadOptions(writeLock = true))
        val seen = HashSet<String>()
        fun label(pkg: Package): String {
            val where = when (val o = pkg.origin) {
                is Origin.Git -> "git ${o.url}#${o.commit.take(7)}"
                is Origin.Path -> o.spec
                is Origin.Local -> Paths.relativize(p.cwd(), pkg.dir).let { if (it == ".") "." else it }
            }
            return "${c.out.bold(pkg.name)} v${pkg.info.version} ${c.out.dim("($where)")}"
        }
        fun walk(pkg: Package, prefix: String) {
            for ((i, d) in pkg.deps.withIndex()) {
                val last = i == pkg.deps.size - 1
                val dup = !seen.add(d.dir) && d.deps.isNotEmpty()
                c.println(prefix + (if (last) "└── " else "├── ") + label(d) + if (dup) " (*)" else "")
                if (!dup) walk(d, prefix + if (last) "    " else "│   ")
            }
        }
        for (pkg in project.selected) {
            seen.add(pkg.dir)
            c.println(label(pkg))
            walk(pkg, "")
        }
        if (seen.size > project.selected.size) { /* repeated subtrees are marked (*) */ }
        return 0
    }

    private fun explain(): Int {
        if (a.has("list")) { c.print(Explain.list(c.out)); return 0 }
        if (a.positionals.isEmpty()) throw UsageError("`mini explain` needs a diagnostic code", "e.g. `mini explain M0102`, or `mini explain --list`")
        val code = Explain.normalize(a.positionals[0])
        val text = Explain.render(code, c.out) ?: throw MiniError(
            "no explanation for `${a.positionals[0]}`",
            suggest(code, Explain.codes())?.let { "did you mean `$it`?" } ?: "list the known codes with `mini explain --list`",
        )
        c.print(text)
        return 0
    }
}
