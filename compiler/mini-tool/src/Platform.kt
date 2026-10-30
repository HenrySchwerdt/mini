package mini.tool

/**
 * Everything the core of `mini` needs from the outside world. The core is pure Kotlin (no `java.*`, see
 * MiniToolPurityTest); today the only implementation is `mini.tool.jvm.JvmPlatform`. After B3 a native one
 * (libc / libuv) replaces it and `mini` itself is compiled by `minic`.
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

/** String-based path helpers (pure, POSIX style). */
object Paths {
    fun isAbsolute(p: String): Boolean = p.startsWith("/")

    fun join(a: String, b: String): String = when {
        b.isEmpty() -> a
        isAbsolute(b) || a.isEmpty() -> b
        a.endsWith("/") -> a + b
        else -> "$a/$b"
    }

    private fun joinAll(parts: Array<out String>): String {
        var r = ""
        for (x in parts) r = join(r, x)
        return r
    }

    fun join(vararg parts: String): String = joinAll(parts)

    /** Removes `.`, resolves `..` lexically and collapses duplicate slashes. */
    fun normalize(p: String): String {
        val abs = isAbsolute(p)
        val out = ArrayList<String>()
        for (seg in p.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {}
                seg == ".." -> if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1) else if (!abs) out += ".."
                else -> out += seg
            }
        }
        val s = out.joinToString("/")
        return if (abs) "/$s" else s.ifEmpty { "." }
    }

    fun parent(p: String): String? {
        val n = normalize(p)
        if (n == "/" || n == ".") return null
        val i = n.lastIndexOf('/')
        return when {
            i < 0 -> "."
            i == 0 -> "/"
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
        val b = normalize(base).split('/').filter { it.isNotEmpty() }
        val t = normalize(p).split('/').filter { it.isNotEmpty() }
        var i = 0
        while (i < b.size && i < t.size && b[i] == t[i]) i++
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
