package mini.tool

import mini.io.createDirectories
import mini.io.deleteRecursively
import mini.io.fileExists
import mini.io.isDirectory
import mini.io.isRegularFile
import mini.io.readTextFile
import mini.io.writeTextFile
import mini.tool.native.NativePlatform

/** The few `java.io.File` operations the tests use, on top of `mini.io`. */
class File(val path: String) {
    constructor(parent: File, child: String) : this((if (parent.path.endsWith("/")) parent.path.dropLast(1) else parent.path) + "/" + child)

    constructor(parent: String, child: String) : this(File(parent), child)

    val name: String get() = path.substringAfterLast('/')
    val parentFile: File? get() = if ('/' in path) File(path.substringBeforeLast('/')) else null
    val isFile: Boolean get() = isRegularFile(path)
    val isDirectory: Boolean get() = isDirectory(path)

    fun exists(): Boolean = fileExists(path)
    fun readText(): String = readTextFile(path)
    fun writeText(text: String) = writeTextFile(path, text)
    fun appendText(text: String) = writeTextFile(path, readTextFile(path) + text)
    fun mkdirs() = createDirectories(path)
    fun delete(): Boolean = mini.io.deletePath(path)
    fun deleteRecursively() = deleteRecursively(path)
}

/** A [NativePlatform] that records output, runs in a temp directory with its own MINI_HOME and never uses colours. */
/** Collected output; the Mini StringBuilder has no `contains`. */
class Buf {
    private val sb = StringBuilder()
    fun append(s: String): Buf { sb.append(s); return this }
    fun setLength(n: Int) { sb.setLength(n) }
    fun contains(s: String): Boolean = sb.toString().contains(s)
    override fun toString(): String = sb.toString()
}

class TestPlatform(val root: File, var dir: File) : NativePlatform() {
    val out = Buf()
    val err = Buf()
    private val extraEnv: Map<String, String> = emptyMap()

    private val envMap: Map<String, String> = mapOf(
        "MINI_ROOT" to nativeHome(),
        "MINI_HOME" to File(root, "home").path,
        "NO_COLOR" to "1",
    ) + extraEnv

    override fun env(name: String): String? = envMap[name] ?: super.env(name)
    override fun cwd(): String = dir.path
    override fun isTerminal(stream: Stream) = false
    override fun write(stream: Stream, text: String) { (if (stream == Stream.Stdout) out else err).append(text) }

    override fun exec(spec: Exec): ExecResult = super.exec(Exec(spec.command, spec.cwd ?: dir.path, envMap + spec.env, spec.capture))

    /** Runs `mini <args>` in [dir]; returns the exit code. Output is appended to [out] / [err]. */
    fun mini(vararg args: String): Int = Mini.run(args.toList(), this)

    fun reset() { out.setLength(0); err.setLength(0) }
    fun all(): String = out.toString() + err.toString()
    fun file(rel: String) = File(dir, rel)
    fun write(rel: String, text: String) { val f = file(rel); f.parentFile!!.mkdirs(); f.writeText(text) }

    companion object {
        private var counter = 0

        /** `mini test` runs a test in the directory of its package (compiler/mini-tool). */
        const val REPO_ROOT = "../.."

        /** A fresh directory below the system temp directory (outside the repository, so no enclosing mini.toml is found). */
        fun temp(): TestPlatform {
            val base = NativePlatform()
            counter++
            val d = (base.env("TMPDIR") ?: "/tmp").removeSuffix("/") + "/mini-test-" + base.nowMillis() + "-" + counter
            createDirectories(d)
            return File(base.realPath(d)).let { TestPlatform(it, it) }
        }

        /** The native toolchain staged by `scripts/build-mini.sh` (bin/minic-native, lib/libminirt.a, include/, stdlib/). */
        fun nativeHome(): String {
            val base = NativePlatform()
            return base.env("MINI_ROOT") ?: base.realPath("$REPO_ROOT/build/native")
        }

        private fun hasTool(name: String): Boolean = NativePlatform().exec(Exec(listOf(name, "--version"))).exitCode == 0

        /** Whether the full toolchain (the native compiler, the runtime, a C compiler) is available for end-to-end tests. */
        fun toolchainAvailable(): Boolean {
            val home = nativeHome()
            if (!isRegularFile("$home/bin/minic-native") || !isRegularFile("$home/lib/libminirt.a")) return false
            return hasTool("clang") || hasTool("gcc")
        }

        fun gitAvailable(): Boolean = hasTool("git")
    }
}

/** Runs git in [dir] for building local test repositories. */
fun runGit(dir: File, vararg args: String): String {
    val cmd = listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false") + args.toList()
    val r = NativePlatform().exec(Exec(cmd, dir.path, mapOf("GIT_CONFIG_GLOBAL" to "/dev/null"), true))
    val text = r.stdout + r.stderr
    check(r.exitCode == 0) { "git ${args.toList().joinToString(" ")} failed: $text" }
    return text.trim()
}
