package mini.tool

import mini.tool.jvm.JvmPlatform
import java.io.File
import java.nio.file.Files

/** A [JvmPlatform] that records output, runs in a temp directory with its own MINI_HOME and never uses colours. */
class TestPlatform(val root: File, var dir: File = root, private val extraEnv: Map<String, String> = emptyMap()) : JvmPlatform() {
    val out = StringBuilder()
    val err = StringBuilder()

    private val envMap: Map<String, String> = mapOf(
        "MINI_ROOT" to nativeHome(),
        "MINI_HOME" to File(root, "home").path,
        "NO_COLOR" to "1",
    ) + extraEnv

    override fun env(name: String): String? = envMap[name] ?: System.getenv(name)
    override fun cwd(): String = dir.absolutePath
    override fun isTerminal(stream: Stream) = false
    override fun write(stream: Stream, text: String) { (if (stream == Stream.Stdout) out else err).append(text) }

    override fun exec(spec: Exec): ExecResult = super.exec(Exec(spec.command, spec.cwd ?: dir.path, envMap + spec.env, spec.capture))

    /** Runs `mini <args>` in [dir]; returns the exit code. Output is appended to [out] / [err]. */
    fun mini(vararg args: String): Int = Mini.run(args.toList(), this)

    fun reset() { out.setLength(0); err.setLength(0) }
    fun all(): String = out.toString() + err.toString()
    fun file(rel: String) = File(dir, rel)
    fun write(rel: String, text: String) { val f = file(rel); f.parentFile.mkdirs(); f.writeText(text) }

    companion object {
        fun temp(): TestPlatform = TestPlatform(Files.createTempDirectory("mini-test").toFile().canonicalFile)

        /** The native toolchain staged by `scripts/build-mini.sh` (bin/minic-native, lib/libminirt.a, include/, stdlib/). */
        fun nativeHome(): String = System.getProperty("mini.nativeHome") ?: error("mini.nativeHome not set")

        /** Whether the full toolchain (the native compiler, the runtime, a C compiler) is available for end-to-end tests. */
        fun toolchainAvailable(): Boolean {
            val home = System.getProperty("mini.nativeHome") ?: return false
            if (!File("$home/bin/minic-native").isFile || !File("$home/lib/libminirt.a").isFile) return false
            return try {
                ProcessBuilder("clang", "--version").redirectErrorStream(true).start().waitFor() == 0 ||
                    ProcessBuilder("gcc", "--version").redirectErrorStream(true).start().waitFor() == 0
            } catch (e: java.io.IOException) { false }
        }

        fun gitAvailable(): Boolean = try { ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor() == 0 } catch (e: java.io.IOException) { false }
    }
}

/** Runs git in [dir] for building local test repositories. */
fun runGit(dir: File, vararg args: String): String {
    val pb = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false") + args)
    pb.directory(dir)
    pb.redirectErrorStream(true)
    pb.environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
    val p = pb.start()
    val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $text" }
    return text.trim()
}
