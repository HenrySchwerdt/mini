package mini.tool

/** `mini doctor`: checks the tools a Mini build needs and says how to fix what is missing. */
class Doctor(val p: Platform, val c: Console) {
    private var problems = 0
    private var warnings = 0

    private fun ok(what: String, detail: String) = c.println("  ${c.out.green("ok", true)}   ${what.padEnd(14)} $detail")
    private fun bad(what: String, detail: String, fix: List<String>) {
        problems++
        c.println("  ${c.out.red("FAIL", true)} ${what.padEnd(14)} $detail")
        for (f in fix) c.println("       ${c.out.cyan("fix:", true)} $f")
    }
    private fun warn(what: String, detail: String, fix: List<String>) {
        warnings++
        c.println("  ${c.out.yellow("warn", true)} ${what.padEnd(14)} $detail")
        for (f in fix) c.println("       ${c.out.cyan("fix:", true)} $f")
    }

    private fun installHint(pkgApt: String, pkgBrew: String): String = when (p.os) {
        "macos" -> "brew install $pkgBrew"
        else -> "sudo apt install $pkgApt   (Debian/Ubuntu; use your distribution's package manager otherwise)"
    }

    private fun javaMajor(): Pair<Int?, String>? {
        val r = p.exec(Exec(listOf("java", "-version")))
        if (r.startError != null && r.exitCode != 0 && r.stderr.isEmpty()) return null
        val text = (r.stderr + "\n" + r.stdout).lines().firstOrNull { it.contains("version") } ?: return Pair(null, "unknown version")
        val v = Regex("\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: return Pair(null, text.trim())
        val major = if (v.startsWith("1.")) v.removePrefix("1.").substringBefore('.').toIntOrNull() else v.substringBefore('.').substringBefore('-').toIntOrNull()
        return Pair(major, v)
    }

    fun run(): Int {
        c.println(c.out.bold("mini ${Mini.VERSION} doctor") + "  (${p.os})")
        c.println()

        val root = Toolchain.findRoot(p)
        if (root != null && p.isDirectory(Paths.join(root, "stdlib")) && p.isFile(Paths.join(root, "runtime/include/minirt.h"))) ok("Mini root", root)
        else bad("Mini root", "stdlib/ and runtime/ not found", listOf("run `mini` through the wrapper script in the repository (`<repo>/mini`), or set MINI_ROOT=<repo>"))

        val minic = p.env("MINIC")?.takeIf { it.isNotEmpty() } ?: root?.let { Paths.join(it, Toolchain.MINIC_RELATIVE) }
        if (minic != null && p.isFile(minic)) ok("minic", minic)
        else bad("minic", "the compiler is not built", listOf("run `./minic --help` in the repository once (builds it), or `cd compiler && ./gradlew installDist`"))

        val cc = Toolchain.findCompiler(p)
        if (cc != null) ok("C compiler", cc.second)
        else bad("C compiler", "neither clang nor gcc found", listOf(installHint("clang", "llvm"), "or set MINI_CC to your compiler"))

        val j = javaMajor()
        if (j == null) bad("JDK", "`java` not found", listOf("minic still runs on the JVM (until the bootstrap B3): " + installHint("openjdk-21-jdk", "openjdk@21")))
        else if (j.first == null || j.first!! < 21) warn("JDK", "found ${j.second}, JDK 21 is required", listOf(installHint("openjdk-21-jdk", "openjdk@21")))
        else ok("JDK", "${j.second} (needed only while minic runs on the JVM)")

        val g = p.exec(Exec(listOf("git", "--version")))
        if (g.exitCode == 0) ok("git", g.stdout.trim()) else warn("git", "not found (only needed for git dependencies)", listOf(installHint("git", "git")))

        val pc = p.exec(Exec(listOf("pkg-config", "--modversion", "libuv")))
        val uvHeader = listOf("/usr/include/uv.h", "/usr/local/include/uv.h", "/opt/homebrew/include/uv.h").firstOrNull { p.isFile(it) }
        if (pc.exitCode == 0) ok("libuv", "version ${pc.stdout.trim()} (only needed for the runtime tests)")
        else if (uvHeader != null) ok("libuv", "$uvHeader (only needed for the runtime tests)")
        else warn("libuv", "not found (only needed for the runtime tests, not for building programs)", listOf(installHint("libuv1-dev", "libuv")))

        val home = miniHome(p)
        val probe = Paths.join(home, ".doctor")
        try {
            p.mkdirs(home)
            p.writeText(probe, "ok\n")
            p.remove(probe)
            ok("MINI_HOME", "$home (git dependencies are cached here)")
        } catch (e: Exception) {
            bad("MINI_HOME", "$home is not writable", listOf("set MINI_HOME to a writable directory"))
        }

        c.println()
        if (problems == 0) {
            c.println(c.out.green("Everything required is in place.", true) + if (warnings > 0) " ($warnings optional item${if (warnings == 1) "" else "s"} to look at)" else "")
            return 0
        }
        c.println(c.out.red("$problems problem${if (problems == 1) "" else "s"} found.", true) + " Fix them and run `mini doctor` again.")
        return 1
    }
}
