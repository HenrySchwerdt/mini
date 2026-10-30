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

    fun run(): Int {
        c.println(c.out.bold("mini ${Mini.VERSION} doctor") + "  (${p.os})")
        c.println()

        val root = Toolchain.findRoot(p)
        if (root != null && p.isDirectory(Paths.join(root, "stdlib")) && p.isFile(Paths.join(root, "include/minirt.h"))) ok("Mini root", root)
        else bad("Mini root", "stdlib/ and include/ not found", listOf("install Mini (see the README), or in a source checkout run `./mini` (it builds the toolchain), or set MINI_ROOT"))

        val minic = p.env("MINIC_NATIVE")?.takeIf { it.isNotEmpty() } ?: root?.let { Paths.join(it, Toolchain.MINIC_RELATIVE) }
        if (minic != null && p.isFile(minic)) ok("minic-native", minic)
        else bad("minic-native", "the compiler is not built", listOf("in a source checkout run `./mini` or `scripts/build-mini.sh`"))
        if (root != null && !p.isFile(Paths.join(root, "lib/libminirt.a"))) bad("runtime", "lib/libminirt.a is missing", listOf("reinstall Mini, or run `scripts/build-mini.sh` in a source checkout"))

        val cc = Toolchain.findCompiler(p)
        if (cc != null) ok("C compiler", cc.second)
        else bad("C compiler", "neither clang nor gcc found", listOf(installHint("clang", "llvm"), "or set MINI_CC to your compiler"))

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
