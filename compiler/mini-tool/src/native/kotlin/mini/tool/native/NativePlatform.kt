package mini.tool.native

import mini.internal.CSymbol
import mini.internal.fileReadText
import mini.internal.fileWriteText
import mini.tool.Exec
import mini.tool.ExecResult
import mini.tool.FileStat
import mini.tool.Mini
import mini.tool.Paths
import mini.tool.Platform
import mini.tool.Stream

/*
 * The native implementation of [Platform]: `mini` compiled by Mini itself. Everything goes through the small C layer
 * compiler/mini-tool/src/native/c/mini_host.c (`@CSymbol`, see stdlib/mini/internal/CSymbol.kt) next to libminirt;
 * the JVM is not involved. The JVM implementation is mini.tool.jvm.JvmPlatform.
 */

@CSymbol("mh_env")
external fun hostEnv(name: String): String?

@CSymbol("mh_cwd")
external fun hostCwd(): String

@CSymbol("mh_exe_path")
external fun hostExePath(): String?

@CSymbol("mh_is_tty")
external fun hostIsTty(fd: Int): Boolean

@CSymbol("mh_terminal_width")
external fun hostTerminalWidth(): Int

@CSymbol("mh_now_millis")
external fun hostNowMillis(): Long

@CSymbol("mh_kind")
external fun hostKind(path: String): Int

@CSymbol("mh_size")
external fun hostSize(path: String): Long

@CSymbol("mh_mtime_millis")
external fun hostMtimeMillis(path: String): Long

@CSymbol("mh_list")
external fun hostList(path: String): String?

@CSymbol("mh_mkdirs")
external fun hostMkdirs(path: String): Boolean

@CSymbol("mh_remove")
external fun hostRemove(path: String): Int

@CSymbol("mh_make_executable")
external fun hostMakeExecutable(path: String)

@CSymbol("mh_realpath")
external fun hostRealPath(path: String): String?

@CSymbol("mh_write")
external fun hostWrite(fd: Int, text: String): Int

@CSymbol("mh_exit")
external fun hostExit(code: Int)

@CSymbol("mh_exec")
external fun hostExec(argv: String, cwd: String?, env: String, capture: Boolean): Int

@CSymbol("mh_exec_stdout")
external fun hostExecStdout(): String?

@CSymbol("mh_exec_stderr")
external fun hostExecStderr(): String?

@CSymbol("mh_exec_error")
external fun hostExecError(): String?

private const val SEP = '\u0001'

class NativePlatform : Platform {
    override val os: String = detectOs()

    override fun env(name: String): String? = hostEnv(name)
    override fun cwd(): String = hostCwd()
    override fun homeDir(): String? = hostEnv("HOME")
    override fun exePath(): String? = hostExePath()

    override fun isTerminal(stream: Stream): Boolean {
        // the ./mini wrapper tells us exactly (`[ -t 1 ]`), without it the file descriptor decides
        val v = hostEnv(if (stream == Stream.Stdout) "MINI_TTY_STDOUT" else "MINI_TTY_STDERR")
        if (v != null) return v == "1"
        return hostIsTty(if (stream == Stream.Stdout) 1 else 2)
    }

    override fun terminalWidth(): Int? {
        val cols = hostEnv("COLUMNS")?.toIntOrNull()
        if (cols != null) return cols
        val w = hostTerminalWidth()
        return if (w > 0) w else null
    }

    override fun nowMillis(): Long = hostNowMillis()

    override fun readText(path: String): String? = if (hostKind(path) == 1) fileReadText(path) else null

    override fun writeText(path: String, text: String) {
        val parent = Paths.parent(Paths.normalize(path))
        if (parent != null && parent != "." && hostKind(parent) != 2) hostMkdirs(parent)
        if (!fileWriteText(path, text)) throw IllegalStateException("cannot write file: " + path)
    }

    override fun exists(path: String): Boolean = hostKind(path) != 0
    override fun isDirectory(path: String): Boolean = hostKind(path) == 2
    override fun isFile(path: String): Boolean = hostKind(path) == 1

    override fun list(dir: String): List<String> {
        val text = hostList(dir) ?: return emptyList()
        return if (text.isEmpty()) emptyList() else text.split(SEP)
    }

    override fun mkdirs(path: String) {
        hostMkdirs(path)
    }

    override fun remove(path: String): Int = hostRemove(path)
    override fun makeExecutable(path: String) = hostMakeExecutable(path)

    override fun realPath(path: String): String {
        val abs = if (Paths.isAbsolute(path)) path else Paths.join(hostCwd(), path)
        return hostRealPath(abs) ?: Paths.normalize(abs)
    }

    override fun stat(path: String): FileStat? {
        if (hostKind(path) == 0) return null
        return FileStat(hostSize(path), hostMtimeMillis(path))
    }

    override fun write(stream: Stream, text: String) {
        val r = hostWrite(if (stream == Stream.Stdout) 1 else 2, text)
        if (r == 1 && stream == Stream.Stdout) hostExit(141) // closed pipe (`mini ... | head`)
    }

    override fun exec(spec: Exec): ExecResult {
        val start = hostNowMillis()
        val envText = StringBuilder()
        var first = true
        for ((k, v) in spec.env) {
            if (!first) envText.append(SEP)
            first = false
            envText.append(k).append('=').append(v)
        }
        val code = hostExec(spec.command.joinToString(SEP.toString()), spec.cwd, envText.toString(), spec.capture)
        val millis = hostNowMillis() - start
        if (code == -1) {
            val why = hostExecError()
            if (why != null) return ExecResult(-1, startError = why + " (" + (spec.command.firstOrNull() ?: "") + ")", millis = millis)
        }
        return ExecResult(code, hostExecStdout() ?: "", hostExecStderr() ?: "", millis)
    }

    private fun detectOs(): String {
        // /System/Library exists on macOS only
        return if (hostKind("/System/Library") == 2) "macos" else "linux"
    }
}

fun main(args: Array<String>) {
    hostExit(Mini.run(args.toList(), NativePlatform()))
}
