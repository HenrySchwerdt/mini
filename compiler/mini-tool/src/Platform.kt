package mini.tool

/**
 * Everything the core of `mini` needs from the outside world. The core is pure Kotlin (no `java.*`, see
 * MiniToolPurityTest); today the only implementation is `mini.tool.jvm.JvmPlatform`. After B3 a native one
 * (libc) replaces it and `mini` itself is compiled by `minic`.
 *
 * Paths are `/`-separated strings. Relative paths are relative to [cwd].
 */
interface Platform {
    /** "linux", "macos" or "windows". */
    val os: String

    fun env(name: String): String?
    fun cwd(): String
    fun homeDir(): String?

    /** Absolute path of the running `mini` executable (the native tool), null when unknown (the JVM tool). */
    fun exePath(): String? = null

    fun isTerminal(stream: Stream): Boolean
    fun terminalWidth(): Int?

    /** Monotonic clock in milliseconds (only differences are meaningful). */
    fun nowMillis(): Long

    /** Text of the file, or null when it does not exist or cannot be read. */
    fun readText(path: String): String?
    fun writeText(path: String, text: String)
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun isFile(path: String): Boolean

    /** Names (not paths) of the entries of a directory, sorted; empty when it does not exist. */
    fun list(dir: String): List<String>
    fun mkdirs(path: String)

    /** Deletes a file or a directory tree; returns the number of files removed. */
    fun remove(path: String): Int
    fun makeExecutable(path: String)

    /** Absolute, normalised path (symbolic links resolved when the file exists). */
    fun realPath(path: String): String

    /** Size in bytes and last modification (milliseconds since the epoch) of a file, null when missing. */
    fun stat(path: String): FileStat?

    /** Writes text to standard output / standard error. */
    fun write(stream: Stream, text: String)

    /** Runs a process to completion. Never throws: a process that cannot be started has `exitCode == -1` and [ExecResult.startError]. */
    fun exec(spec: Exec): ExecResult
}

enum class Stream { Stdout, Stderr }

class FileStat(val size: Long, val modifiedMillis: Long)

/**
 * A process to run. With [capture] the output is collected in the result; without it the child inherits the
 * terminal (`mini run`).
 */
class Exec(
    val command: List<String>,
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val capture: Boolean = true,
)

class ExecResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val millis: Long = 0,
    val startError: String? = null,
)

/** Pure path helpers with canonical forward slashes and POSIX, drive and UNC roots. */
object Paths {
    private fun slashes(p: String): String = p.replace('\\', '/')

    /** Canonical roots retain their slash, including drive and UNC share roots. */
    private fun root(p: String): String {
        if (p.length >= 3 && p[1] == ':' && p[2] == '/') return p.substring(0, 3)
        if (p.startsWith("//") && !p.startsWith("///")) {
            val start = if (p.length >= 8 && p.substring(0, 8).equals("//?/UNC/", ignoreCase = true)) 8 else 2
            val server = p.indexOf('/', start)
            if (server > start) {
                val share = p.indexOf('/', server + 1)
                if (share > server + 1) return p.substring(0, share + 1)
                if (share < 0 && p.length > server + 1) return p + "/"
            }
        }
        return if (p.startsWith("/")) "/" else ""
    }

    fun isAbsolute(p: String): Boolean = root(slashes(p)).isNotEmpty()

    fun join(a: String, b: String): String {
        val left = slashes(a)
        val right = slashes(b)
        return when {
            right.isEmpty() -> left
            right.startsWith("/") && !right.startsWith("//") && root(left).length > 1 -> root(left).removeSuffix("/") + right
            isAbsolute(right) || left.isEmpty() -> right
            left.endsWith("/") -> left + right
            else -> "$left/$right"
        }
    }

    private fun joinAll(parts: Array<out String>): String {
        var r = ""
        for (x in parts) r = join(r, x)
        return r
    }

    fun join(vararg parts: String): String = joinAll(parts)

    /** Removes `.`, resolves `..` lexically and collapses duplicate slashes. */
    fun normalize(p: String): String {
        val path = slashes(p)
        val prefix = root(path)
        val out = ArrayList<String>()
        for (seg in path.substring(if (prefix.length > path.length) path.length else prefix.length).split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (prefix.isEmpty()) out += ".."
                else -> out += seg
            }
        }
        val s = out.joinToString("/")
        return if (prefix.isNotEmpty()) prefix + s else s.ifEmpty { "." }
    }

    fun parent(p: String): String? {
        val n = normalize(p)
        val prefix = root(n)
        if (n == prefix || n == ".") return null
        val i = n.lastIndexOf('/')
        return when {
            i < 0 -> "."
            i < prefix.length -> prefix
            else -> n.substring(0, i)
        }
    }

    fun name(p: String): String = normalize(p).substringAfterLast('/')

    fun extension(p: String): String {
        val n = name(p)
        val i = n.lastIndexOf('.')
        return if (i <= 0) "" else n.substring(i + 1)
    }

    /** [p] relative to [base] (both absolute and normalised), with `..` segments where needed. */
    fun relativize(base: String, p: String): String {
        val nb = normalize(base)
        val nt = normalize(p)
        // A path on another drive/share cannot be expressed relative to this root.
        if (!root(nb).equals(root(nt), ignoreCase = true)) return nt
        val b = nb.substring(root(nb).length).split('/').filter { it.isNotEmpty() }
        val t = nt.substring(root(nt).length).split('/').filter { it.isNotEmpty() }
        val windowsRoot = root(nb).length > 1
        var i = 0
        while (i < b.size && i < t.size && b[i].equals(t[i], ignoreCase = windowsRoot)) i++
        val parts = ArrayList<String>()
        repeat(b.size - i) { parts += ".." }
        parts.addAll(t.subList(i, t.size))
        return if (parts.isEmpty()) "." else parts.joinToString("/")
    }

    /** Resolves [p] against [base] and normalises the result. */
    fun resolve(base: String, p: String): String = normalize(join(base, p))
}

/** Every `.kt` file below [dir] (sorted, relative to [dir] when [relative]); hidden directories and `build` are skipped. */
fun Platform.kotlinFiles(dir: String, relative: Boolean = false): List<String> {
    val out = ArrayList<String>()
    fun walk(d: String, rel: String) {
        for (n in list(d)) {
            val full = Paths.join(d, n)
            val r = if (rel.isEmpty()) n else "$rel/$n"
            if (isDirectory(full)) {
                if (n.startsWith(".") || n == "build") continue
                walk(full, r)
            } else if (n.endsWith(".kt")) out += if (relative) r else full
        }
    }
    walk(dir, "")
    return out.sorted()
}
