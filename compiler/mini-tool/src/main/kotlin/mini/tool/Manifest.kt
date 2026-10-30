package mini.tool

enum class Kind(val text: String) { App("app"), Lib("lib") }
enum class MemoryDefault(val text: String) { Managed("managed"), Strict("strict") }

/** How a git dependency selects its commit. */
sealed class GitRef {
    object Default : GitRef()
    class Tag(val name: String) : GitRef()
    class Rev(val rev: String) : GitRef()
    class Branch(val name: String) : GitRef()

    /** Stable text stored in the lock file to detect that the manifest changed. */
    fun spec(): String = when (this) {
        is Default -> "default"
        is Tag -> "tag:$name"
        is Rev -> "rev:$rev"
        is Branch -> "branch:$name"
    }
}

sealed class DepSpec {
    abstract val name: String
    abstract val span: Span
}
class PathDep(override val name: String, val path: String, override val span: Span) : DepSpec()
class GitDep(override val name: String, val url: String, val ref: GitRef, override val span: Span) : DepSpec()

class PackageInfo(
    val name: String,
    val version: String,
    val kind: Kind,
    val memory: MemoryDefault,
    val nameSpan: Span,
)

class WorkspaceInfo(val members: List<String>, val span: Span)

/** A parsed `mini.toml`. [file] is the absolute path of the manifest, [dir] its directory. */
class Manifest(
    val file: String,
    val dir: String,
    val source: String,
    val pkg: PackageInfo?,
    val deps: List<DepSpec>,
    val workspace: WorkspaceInfo?,
)

/** A manifest problem, ready to print. */
class ManifestException(val error: TomlError, val file: String, val source: String) : Exception(error.msg) {
    fun render(style: Style, displayName: String = file): String = renderSourceError(error, displayName, source, style)
}

object ManifestParser {
    private val packageKeys = listOf("name", "version", "kind", "memory", "description", "authors", "license", "repository", "homepage")
    private val topKeys = listOf("package", "dependencies", "workspace")
    private val depKeys = listOf("path", "git", "rev", "tag", "branch")

    /** Parses and validates [text]. Throws [ManifestException] with a position for every problem. */
    fun parse(file: String, text: String): Manifest {
        try {
            return build(file, text, parseToml(text))
        } catch (e: TomlError) {
            throw ManifestException(e, file, text)
        }
    }

    fun isValidName(n: String): Boolean =
        n.isNotEmpty() && n[0] in 'a'..'z' && n.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }

    fun isValidVersion(v: String): Boolean =
        isSemanticVersion(v)

    private fun unknown(kind: String, e: TEntry, known: List<String>, where: String): Nothing {
        val s = suggest(e.key, known)
        throw TomlError(
            "unknown $kind `${e.key}` in $where",
            e.keySpan,
            "not recognised",
            if (s != null) "did you mean `$s`?" else "known ${kind}s: ${known.joinToString(", ") { "`$it`" }}",
        )
    }

    private fun str(v: TomlValue, what: String, example: String): TStr =
        v as? TStr ?: throw TomlError("$what must be a string", v.span, "expected a string", "write it as $example")

    private fun build(file: String, text: String, root: TTable): Manifest {
        val dir = Paths.parent(file) ?: "/"
        for (e in root.entries.values) if (e.key !in topKeys) unknown("section", e, topKeys, "mini.toml")
        var pkg: PackageInfo? = null
        val pt = root["package"]
        if (pt != null) {
            val t = pt as? TTable ?: throw TomlError("`package` must be a table", pt.span, null, "write `[package]` on its own line")
            for (e in t.entries.values) if (e.key !in packageKeys) unknown("key", e, packageKeys, "[package]")
            val nameV = t["name"] ?: throw TomlError("`[package]` needs a `name`", t.span, "in this table", "add `name = \"my-app\"`")
            val name = str(nameV, "`name`", "`name = \"my-app\"`")
            if (!isValidName(name.value)) {
                throw TomlError(
                    "invalid package name `${name.value}`", name.span, "not a valid name",
                    "names start with a lowercase letter and contain only a-z, 0-9, `_` and `-`" +
                        (if (name.value.isNotEmpty()) "; try `${sanitizeName(name.value)}`" else ""),
                )
            }
            val version = t["version"]?.let { str(it, "`version`", "`version = \"0.1.0\"`") }
            if (version != null && !isValidVersion(version.value)) {
                throw TomlError("invalid version `${version.value}`", version.span, "not a version", "use `MAJOR.MINOR.PATCH`, e.g. \"0.1.0\"")
            }
            val kindV = t["kind"]?.let { str(it, "`kind`", "`kind = \"app\"` or `kind = \"lib\"`") }
            val kind = when (kindV?.value) {
                null, "app" -> Kind.App
                "lib" -> Kind.Lib
                else -> throw TomlError(
                    "invalid kind `${kindV.value}`", kindV.span, "expected `app` or `lib`",
                    suggest(kindV.value, listOf("app", "lib"))?.let { "did you mean `$it`?" } ?: "`app` builds an executable, `lib` is a source package for other projects",
                )
            }
            val memV = t["memory"]?.let { str(it, "`memory`", "`memory = \"managed\"` or `memory = \"strict\"`") }
            val memory = when (memV?.value) {
                null, "managed" -> MemoryDefault.Managed
                "strict" -> MemoryDefault.Strict
                else -> throw TomlError(
                    "invalid memory mode `${memV.value}`", memV.span, "expected `managed` or `strict`",
                    suggest(memV.value, listOf("managed", "strict"))?.let { "did you mean `$it`?" }
                        ?: "`managed` uses reference counting, `strict` makes every allocation need a proven owner (docs/memory-model.md)",
                )
            }
            pkg = PackageInfo(name.value, version?.value ?: "0.1.0", kind, memory, name.span)
        }

        val deps = ArrayList<DepSpec>()
        val dt = root["dependencies"]
        if (dt != null) {
            val t = dt as? TTable ?: throw TomlError("`dependencies` must be a table", dt.span, null, "write `[dependencies]` on its own line")
            for (e in t.entries.values) deps += parseDep(e)
        }

        var ws: WorkspaceInfo? = null
        val wt = root["workspace"]
        if (wt != null) {
            val t = wt as? TTable ?: throw TomlError("`workspace` must be a table", wt.span, null, "write `[workspace]` on its own line")
            for (e in t.entries.values) if (e.key != "members") unknown("key", e, listOf("members"), "[workspace]")
            val m = t["members"] ?: throw TomlError("`[workspace]` needs `members`", t.span, "in this table", "add `members = [\"app\", \"lib\"]`")
            val arr = m as? TArray ?: throw TomlError("`members` must be an array of paths", m.span, "expected an array", "write `members = [\"app\", \"lib\"]`")
            val members = arr.items.map { str(it, "a workspace member", "\"path/to/member\"").value }
            ws = WorkspaceInfo(members, t.entries["members"]!!.keySpan)
        }
        if (pkg == null && ws == null) {
            throw TomlError("the manifest has neither `[package]` nor `[workspace]`", Span(1, 1), null, "add a `[package]` section with `name = \"...\"`, or run `mini init`")
        }
        return Manifest(file, dir, text, pkg, deps, ws)
    }

    private fun parseDep(e: TEntry): DepSpec {
        val v = e.value
        if (v is TStr) {
            throw TomlError(
                "dependency `${e.key}` needs a source, not just a string", v.span, "expected a table",
                "write `${e.key} = { path = \"${v.value}\" }` or `${e.key} = { git = \"<url>\", tag = \"v1.0\" }`",
            )
        }
        val t = v as? TTable ?: throw TomlError("dependency `${e.key}` must be a table", v.span, "expected `{ path = ... }` or `{ git = ... }`", null)
        if (!ManifestParser.isValidName(e.key)) {
            throw TomlError("invalid dependency name `${e.key}`", e.keySpan, "not a valid name", "package names use a-z, 0-9, `_` and `-`")
        }
        for (k in t.entries.values) if (k.key !in depKeys) unknown("key", k, depKeys, "dependency `${e.key}`")
        val path = t["path"]
        val git = t["git"]
        if (path != null && git != null) {
            throw TomlError("dependency `${e.key}` has both `path` and `git`", e.keySpan, "choose one", "remove `path` or `git`")
        }
        if (path == null && git == null) {
            throw TomlError("dependency `${e.key}` has no source", e.keySpan, "needs `path` or `git`", "write `${e.key} = { path = \"../${e.key}\" }` or `${e.key} = { git = \"<url>\" }`")
        }
        if (path != null) {
            for (k in listOf("rev", "tag", "branch")) t.entries[k]?.let {
                throw TomlError("`$k` only applies to git dependencies", it.keySpan, "not allowed with `path`", "remove it, or use `git = \"<url>\"` instead of `path`")
            }
            return PathDep(e.key, str(path, "`path`", "`path = \"../${e.key}\"`").value, e.keySpan)
        }
        val url = str(git!!, "`git`", "`git = \"https://github.com/you/${e.key}\"`").value
        val given = listOf("rev", "tag", "branch").filter { t[it] != null }
        if (given.size > 1) {
            throw TomlError("dependency `${e.key}` pins the revision more than once (${given.joinToString(", ")})", e.keySpan, "choose one", "keep only one of `rev`, `tag`, `branch`")
        }
        val ref = when (given.firstOrNull()) {
            "rev" -> GitRef.Rev(str(t["rev"]!!, "`rev`", "`rev = \"abc1234\"`").value)
            "tag" -> GitRef.Tag(str(t["tag"]!!, "`tag`", "`tag = \"v1.0\"`").value)
            "branch" -> GitRef.Branch(str(t["branch"]!!, "`branch`", "`branch = \"main\"`").value)
            else -> GitRef.Default
        }
        return GitDep(e.key, url, ref, e.keySpan)
    }

    /** Best-effort package name from arbitrary text: `My App!` -> `my-app`. */
    fun sanitizeName(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) sb.append(if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') c else '-')
        var r = sb.toString().trimChars('-', '_')
        while (r.isNotEmpty() && r[0] in '0'..'9') r = r.substring(1)
        return r.collapseRuns('-').ifEmpty { "app" }
    }
}

private fun sanitizeName(s: String) = ManifestParser.sanitizeName(s)

/** The package name as a Kotlin identifier (`my-lib` -> `my_lib`). */
fun String.toIdentifier(): String = replace('-', '_')
