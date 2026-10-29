package mini.tool.jvm

import mini.tool.Exec
import mini.tool.ExecResult
import mini.tool.FileStat
import mini.tool.Platform
import mini.tool.Stream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths as JPaths
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The JVM implementation of [Platform]: the only place of `mini` that touches `java.*`.
 * After the bootstrap (B3) a native implementation replaces it.
 */
open class JvmPlatform : Platform {
    private val stdout = PrintStream(FileOutputStream(FileDescriptor.out), false, "UTF-8")
    private val stderr = PrintStream(FileOutputStream(FileDescriptor.err), false, "UTF-8")

    override val os: String = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("mac") || it.contains("darwin") -> "macos"
            it.contains("win") -> "windows"
            else -> "linux"
        }
    }

    override fun env(name: String): String? = System.getenv(name)
    override fun cwd(): String = File(System.getProperty("user.dir")).absolutePath
    override fun homeDir(): String? = System.getProperty("user.home")

    override fun isTerminal(stream: Stream): Boolean {
        // the ./mini wrapper tells us exactly (`[ -t 1 ]`); without it the JVM can only see "stdin and stdout are a terminal"
        val v = System.getenv(if (stream == Stream.Stdout) "MINI_TTY_STDOUT" else "MINI_TTY_STDERR")
        if (v != null) return v == "1"
        return System.console() != null
    }

    override fun terminalWidth(): Int? = System.getenv("COLUMNS")?.toIntOrNull()
    override fun nowMillis(): Long = System.nanoTime() / 1_000_000

    override fun readText(path: String): String? = try {
        val f = File(path)
        if (f.isFile) f.readText() else null
    } catch (e: java.io.IOException) {
        null
    }

    override fun writeText(path: String, text: String) {
        val f = File(path)
        f.absoluteFile.parentFile?.mkdirs()
        f.writeText(text)
    }

    override fun exists(path: String) = File(path).exists()
    override fun isDirectory(path: String) = File(path).isDirectory
    override fun isFile(path: String) = File(path).isFile
    override fun list(dir: String): List<String> = File(dir).list()?.sorted() ?: emptyList()
    override fun mkdirs(path: String) { File(path).mkdirs() }

    override fun remove(path: String): Int {
        val f = File(path)
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return 0
        var n = 0
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            for (x in f.walkBottomUp()) { if (x.isFile || Files.isSymbolicLink(x.toPath())) n++; x.delete() }
        } else { f.delete(); n = 1 }
        return n
    }

    override fun makeExecutable(path: String) { File(path).setExecutable(true) }

    override fun realPath(path: String): String {
        val f = File(path).absoluteFile
        return try { f.toPath().toRealPath().toString() } catch (e: java.io.IOException) { JPaths.get(f.path).normalize().toString() }
    }

    override fun stat(path: String): FileStat? {
        val f = File(path)
        return if (f.exists()) FileStat(f.length(), f.lastModified()) else null
    }

    override fun write(stream: Stream, text: String) {
        val s = if (stream == Stream.Stdout) stdout else stderr
        s.print(text)
        s.flush()
        if (s.checkError() && stream == Stream.Stdout) exitProcess(141) // closed pipe (`mini ... | head`)
    }

    override fun exec(spec: Exec): ExecResult {
        val start = System.nanoTime()
        val pb = ProcessBuilder(spec.command)
        spec.cwd?.let { pb.directory(File(it)) }
        pb.environment().putAll(spec.env)
        if (spec.capture) {
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null").takeIf { it.exists() } ?: File("NUL")))
        } else {
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT)
        }
        val proc = try { pb.start() } catch (e: java.io.IOException) {
            return ExecResult(-1, startError = e.message ?: "cannot start ${spec.command.firstOrNull()}")
        }
        if (!spec.capture) {
            val code = proc.waitFor()
            return ExecResult(code, millis = (System.nanoTime() - start) / 1_000_000)
        }
        var err = ""
        val t = Thread { err = proc.errorStream.readBytes().toString(Charsets.UTF_8) }
        t.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = proc.waitFor()
        t.join(TimeUnit.SECONDS.toMillis(10))
        return ExecResult(code, out, err, (System.nanoTime() - start) / 1_000_000)
    }
}
