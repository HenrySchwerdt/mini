package mini.tool

sealed class Origin {
    /** A workspace member or the root package. */
    object Local : Origin()
    class Path(val spec: String) : Origin()
    class Git(val url: String, val commit: String, val spec: String) : Origin()
}

/** A package of the dependency graph: parsed manifest plus resolved dependencies. */
class Package(val manifest: Manifest, val info: PackageInfo, val origin: Origin) {
    val dir: String get() = manifest.dir
    val name: String get() = info.name
    val id: String get() = "${info.name} v${info.version}"
    var deps: List<Package> = emptyList()

    fun sourceDir() = Paths.join(dir, "src")
    fun testDir() = Paths.join(dir, "tests")
    fun sources(p: Platform): List<String> = p.kotlinFiles(sourceDir())
    fun testSources(p: Platform): List<String> = p.kotlinFiles(testDir())

    /** The packages this one needs (itself last), dependencies before dependents, each once. */
    fun closure(): List<Package> {
        val out = ArrayList<Package>()
        val seen = HashSet<String>()
        fun visit(x: Package) {
            if (!seen.add(x.dir)) return
            x.deps.forEach { visit(it) }
            out += x
        }
        visit(this)
        return out
    }
}

class Project(
    /** The directory with the workspace `mini.toml` (or of the single package): `build/` and `mini.lock` live here. */
    val root: String,
    val rootManifest: Manifest,
    val members: List<Package>,
    /** The members the command applies to. */
    val selected: List<Package>,
    val lock: Lockfile,
    val isWorkspace: Boolean,
) {
    val buildDir: String get() = Paths.join(root, "build")
    fun lockFile() = Paths.join(root, "mini.lock")

    /** Every package of the graph (members and dependencies), each once. */
    fun allPackages(): List<Package> {
        val out = LinkedHashMap<String, Package>()
        for (m in members) for (x in m.closure()) out.putIfAbsent(x.dir, x)
        return out.values.toList()
    }
}

class LoadOptions(
    val startDir: String,
    val manifestPath: String? = null,
    val packageNames: List<String> = emptyList(),
    val offline: Boolean = false,
    val locked: Boolean = false,
    /** `mini update`: ignore the pins of these dependencies (all when empty and [updateAll]). */
    val update: Set<String> = emptySet(),
    val updateAll: Boolean = false,
    /** Do not write mini.lock (commands that only read). */
    val writeLock: Boolean = true,
)

class ProjectLoader(val p: Platform, val c: Console) {
    val home = miniHome(p)

    fun findManifest(start: String): String? {
        var d: String? = Paths.normalize(if (Paths.isAbsolute(start)) start else Paths.join(p.cwd(), start))
        while (d != null) {
            val f = Paths.join(d, "mini.toml")
            if (p.isFile(f)) return f
            d = Paths.parent(d)
        }
        return null
    }

    fun readManifest(file: String): Manifest {
        val text = p.readText(file) ?: throw MiniError("cannot read ${display(file)}")
        try {
            return ManifestParser.parse(file, text)
        } catch (e: ManifestException) {
            throw MiniError(e.message ?: "invalid manifest", rendered = e.render(c.err, display(file)))
        }
    }

    /** Shortest sensible spelling of [path] for messages: relative to the cwd when it is below it. */
    fun display(path: String): String {
        val rel = Paths.relativize(p.cwd(), path)
        return if (rel.startsWith("../..") || Paths.isAbsolute(rel)) path else rel
    }

    private fun errAt(m: Manifest, span: Span, msg: String, label: String?, hint: String?): MiniError =
        MiniError(msg, rendered = renderSourceError(TomlError(msg, span, label, hint), display(m.file), m.source, c.err))

    private fun expandMembers(ws: Manifest): List<String> {
        val out = ArrayList<String>()
        val info = ws.workspace!!
        for (spec in info.members) {
            if (spec.endsWith("/*")) {
                val base = Paths.resolve(ws.dir, spec.removeSuffix("/*"))
                for (n in p.list(base)) if (p.isFile(Paths.join(base, n, "mini.toml"))) out += Paths.join(base, n)
            } else {
                out += Paths.resolve(ws.dir, spec)
            }
        }
        return out.distinct()
    }

    /** The workspace manifest above [m] that lists it as a member, if any. */
    private fun findWorkspaceRoot(m: Manifest): Manifest? {
        var d = Paths.parent(m.dir)
        while (d != null) {
            val f = Paths.join(d, "mini.toml")
            if (p.isFile(f)) {
                val cand = try { ManifestParser.parse(f, p.readText(f) ?: "") } catch (e: ManifestException) { null }
                if (cand?.workspace != null && expandMembers(cand).any { it == m.dir }) return cand
            }
            d = Paths.parent(d)
        }
        return null
    }

    /** The nearest manifest and the manifest of its workspace root (the same one when there is no workspace). */
    fun locate(o: LoadOptions): Pair<Manifest, Manifest> {
        val manifestFile = if (o.manifestPath != null) {
            val f = Paths.resolve(p.cwd(), o.manifestPath)
            if (!p.isFile(f)) throw MiniError("manifest `${o.manifestPath}` does not exist")
            f
        } else findManifest(o.startDir) ?: throw MiniError(
            "could not find `mini.toml` in `${display(Paths.resolve(p.cwd(), o.startDir))}` or any parent directory",
            "create a project with `mini new <name>`, or turn this directory into one with `mini init`",
        )
        val found = readManifest(manifestFile)
        return Pair(found, if (found.workspace != null) found else findWorkspaceRoot(found) ?: found)
    }

    fun load(o: LoadOptions): Project {
        val (found, rootManifest) = locate(o)
        val isWorkspace = rootManifest.workspace != null
        val dirs = ArrayList<String>()
        if (rootManifest.pkg != null) dirs += rootManifest.dir
        if (isWorkspace) dirs += expandMembers(rootManifest)
        val byDir = LinkedHashMap<String, Package>()
        val named = HashMap<String, Package>()
        for (d in dirs) {
            val m = if (d == rootManifest.dir) rootManifest else {
                if (!p.isFile(Paths.join(d, "mini.toml"))) {
                    throw errAt(rootManifest, rootManifest.workspace!!.span, "workspace member `${Paths.relativize(rootManifest.dir, d)}` has no mini.toml", "listed in `members`",
                        "create it with `mini new ${Paths.relativize(rootManifest.dir, d)}` or remove it from `members`")
                }
                readManifest(Paths.join(d, "mini.toml"))
            }
            val info = m.pkg ?: throw errAt(m, Span(1, 1), "`${display(m.file)}` is a workspace member but has no `[package]`", null, "add `[package]` with a `name`")
            val dup = named[info.name]
            if (dup != null) throw MiniError("two workspace members are named `${info.name}`: ${display(dup.dir)} and ${display(m.dir)}", "rename one of them in its mini.toml")
            val pkg = Package(m, info, Origin.Local)
            byDir[d] = pkg
            named[info.name] = pkg
        }
        val members = byDir.values.toList()
        if (members.isEmpty()) throw MiniError("the workspace has no members", "add `members = [\"app\"]` to `[workspace]` in ${display(rootManifest.file)}")

        val oldLock = Lockfile.parse(p.readText(Paths.join(rootManifest.dir, "mini.lock")) ?: "")
        val resolver = Resolver(p, c, this, GitCache(p, c, home, o.offline), oldLock, o, byDir, named)
        for (m in members) resolver.resolve(m, listOf(m.dir))
        val newLock = Lockfile(resolver.lockOut)
        val lockPath = Paths.join(rootManifest.dir, "mini.lock")
        val changed = !newLock.sameAs(oldLock) && (newLock.git.isNotEmpty() || p.exists(lockPath))
        if (changed) {
            if (o.locked) throw MiniError("`mini.lock` needs to be updated but `--locked` was passed", "run without `--locked` once and commit the new mini.lock")
            if (o.writeLock) {
                if (newLock.git.isEmpty()) p.remove(lockPath) else p.writeText(lockPath, newLock.render())
            }
        }

        val selected: List<Package> = when {
            o.packageNames.isNotEmpty() -> o.packageNames.map { n ->
                members.firstOrNull { it.name == n } ?: throw MiniError(
                    "no package named `$n` in this workspace",
                    suggest(n, members.map { it.name })?.let { "did you mean `$it`?" } ?: "members: " + members.joinToString(", ") { "`${it.name}`" },
                )
            }
            isWorkspace && found.dir != rootManifest.dir -> listOf(byDir[found.dir]!!)
            else -> members
        }
        return Project(rootManifest.dir, rootManifest, members, selected, newLock, isWorkspace)
    }
}

private class Resolver(
    val p: Platform,
    val c: Console,
    val loader: ProjectLoader,
    val git: GitCache,
    val oldLock: Lockfile,
    val o: LoadOptions,
    val byDir: MutableMap<String, Package>,
    val named: MutableMap<String, Package>,
) {
    val lockOut = LinkedHashMap<String, LockEntry>()
    private val done = HashSet<String>()

    private fun fail(m: Manifest, spec: DepSpec, msg: String, label: String?, hint: String?): Nothing {
        throw MiniError(msg, rendered = renderSourceError(TomlError(msg, spec.span, label, hint), loader.display(m.file), m.source, c.err))
    }

    fun resolve(pkg: Package, stack: List<String>) {
        if (!done.add(pkg.dir)) return
        val deps = ArrayList<Package>()
        for (spec in pkg.manifest.deps) {
            val dep = when (spec) {
                is PathDep -> resolvePath(pkg, spec)
                is GitDep -> resolveGit(pkg, spec)
            }
            val cycle = stack.indexOf(dep.dir)
            if (cycle >= 0) {
                val names = (stack.drop(cycle).map { byDir[it]?.name ?: Paths.name(it) } + dep.name).joinToString(" -> ")
                fail(pkg.manifest, spec, "dependency cycle: $names", "closes the cycle", "libraries cannot depend on each other in a circle; move the shared code into a third package")
            }
            resolve(dep, stack + dep.dir)
            deps += dep
        }
        pkg.deps = deps
    }

    private fun register(owner: Package, spec: DepSpec, dir: String, origin: Origin): Package {
        byDir[dir]?.let { existing ->
            if (existing.name != spec.name) fail(owner.manifest, spec, "dependency `${spec.name}` is the package `${existing.name}`", "name does not match", "use `${existing.name} = { ... }` as the key")
            return existing
        }
        val mf = dir + "/mini.toml"
        if (!p.isFile(mf)) {
            val shown = when (spec) { is PathDep -> spec.path; is GitDep -> spec.url }
            fail(owner.manifest, spec, "dependency `${spec.name}` ($shown) has no mini.toml", "no manifest at ${loader.display(dir)}",
                if (spec is PathDep) "check the path; it is relative to ${loader.display(owner.dir)}" else "the repository must contain a mini.toml at its root")
        }
        val m = loader.readManifest(mf)
        val info = m.pkg ?: fail(owner.manifest, spec, "dependency `${spec.name}` has no `[package]` in its mini.toml", "this dependency", "${loader.display(m.file)} must declare `[package]`")
        if (info.name != spec.name) {
            fail(owner.manifest, spec, "dependency `${spec.name}` is called `${info.name}` in ${loader.display(m.file)}", "name does not match", "use `${info.name} = { ... }` here")
        }
        if (info.kind != Kind.Lib) {
            fail(owner.manifest, spec, "dependency `${spec.name}` is an app, not a library", "apps cannot be dependencies", "set `kind = \"lib\"` in ${loader.display(m.file)}")
        }
        named[info.name]?.let { other ->
            if (other.dir != dir) {
                fail(owner.manifest, spec, "two different packages are named `${info.name}`: ${loader.display(other.dir)} and ${loader.display(dir)}", "conflicts with the other `${info.name}`", "package names must be unique across the dependency graph")
            }
        }
        val pkg = Package(m, info, origin)
        byDir[dir] = pkg
        named[info.name] = pkg
        return pkg
    }

    private fun resolvePath(owner: Package, spec: PathDep): Package {
        val dir = p.realPath(Paths.resolve(owner.dir, spec.path))
        return register(owner, spec, dir, Origin.Path(spec.path))
    }

    private fun resolveGit(owner: Package, spec: GitDep): Package {
        val cloneUrl = if (spec.url.startsWith("./") || spec.url.startsWith("../")) Paths.resolve(owner.dir, spec.url) else spec.url
        val ref = spec.ref
        val locked = oldLock.git[spec.name]
        val pinned = locked != null && locked.url == spec.url && locked.spec == ref.spec() &&
            !(o.updateAll || spec.name in o.update)
        val commit = if (pinned) locked!!.commit else {
            if (o.locked) {
                fail(owner.manifest, spec, "dependency `${spec.name}` is not pinned in mini.lock and `--locked` was passed", "not locked", "run without `--locked` once to create the pin")
            }
            val refresh = o.updateAll || spec.name in o.update || ref is GitRef.Default || ref is GitRef.Branch
            try {
                git.resolve(spec.url, cloneUrl, ref, refresh)
            } catch (e: MiniError) {
                throw MiniError(e.msg, e.help ?: "required by `${owner.name}`")
            }
        }
        lockOut[spec.name] = LockEntry(spec.url, ref.spec(), commit)
        val dir = try {
            git.checkout(spec.url, cloneUrl, commit)
        } catch (e: MiniError) {
            throw MiniError(e.msg, e.help)
        }
        return register(owner, spec, p.realPath(dir), Origin.Git(spec.url, commit, ref.spec()))
    }
}
