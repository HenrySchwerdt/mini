package mini.tool

/** An option. [value] is the placeholder of its argument (`<NAME>`), null for a switch. */
class Flag(
    val long: String,
    val help: String,
    val short: Char? = null,
    val value: String? = null,
    /** Allowed values (for validation and shell completion). */
    val choices: List<String>? = null,
    val repeatable: Boolean = false,
)

class Command(
    val name: String,
    val summary: String,
    /** Positional arguments as shown in usage, e.g. `<NAME>` or `[FILTER]`. */
    val args: String,
    val flags: List<Flag>,
    val details: String = "",
    val examples: List<String> = emptyList(),
    /** Whether `--` passes the rest to the program. */
    val passthrough: Boolean = false,
    val minPositionals: Int = 0,
    val maxPositionals: Int = 0,
)

class Parsed(
    val command: Command,
    private val flags: Map<String, List<String>>,
    val positionals: List<String>,
    /** Arguments after `--`. */
    val rest: List<String>,
) {
    fun has(long: String) = flags.containsKey(long)
    fun value(long: String): String? = flags[long]?.lastOrNull()?.takeIf { it.isNotEmpty() }
    fun values(long: String): List<String> = flags[long] ?: emptyList()
}

/** A command-line mistake: printed with `error:`/`help:` and exit code 2. */
class UsageError(val msg: String, val help: String? = null) : Exception(msg)

object CommandLine {
    val global = listOf(
        Flag("quiet", "Print only errors", 'q'),
        Flag("verbose", "Also print the commands that are run", 'v'),
        Flag("color", "Colored output: auto, always or never", value = "<WHEN>", choices = listOf("auto", "always", "never")),
        Flag("help", "Print help", 'h'),
        Flag("version", "Print the version", 'V'),
    )

    private val pkgFlag = Flag("package", "Select a workspace member (default: all, or the one in the current directory)", 'p', "<NAME>", repeatable = true)
    private val manifestFlag = Flag("manifest-path", "Path to the mini.toml to use", value = "<PATH>")
    private val offlineFlag = Flag("offline", "Never access the network; use only cached git repositories")
    private val lockedFlag = Flag("locked", "Fail if mini.lock would have to change (for CI)")
    private val memFlag = Flag("strict", "Treat the project's own code as Strict memory mode (checker on)")
    private val werrorFlag = Flag("Werror", "Treat memory-checker warnings as errors")
    private val formatFlag = Flag("message-format", "Diagnostics format: human or json (one JSON object per line, for editors)", value = "<FMT>", choices = listOf("human", "json"))
    private val releaseFlag = Flag("release", "Optimised build (build/release/, clang -O2)")
    private val backendFlag = Flag("backend", "Compiler backend: llvm-api (default for API releases) or c", value = "<BACKEND>", choices = listOf("llvm-api", "c"))

    private val targetFlag = Flag("target", "Build for another target: wasm32 (a library for JavaScript; needs `kind = \"wasm-lib\"` or --lib)", value = "<TARGET>", choices = listOf("wasm32"))
    private val wasmLibFlag = Flag("lib", "With --target wasm32: build the package as a WebAssembly library (its @WasmExport functions)")
    private val templateFlag = Flag("template", "Project template: app, lib or wasm-lib (a WebAssembly module for JavaScript)", value = "<TEMPLATE>", choices = listOf("app", "lib", "wasm-lib"))

    private val buildFlags = listOf(pkgFlag, releaseFlag, backendFlag, memFlag, werrorFlag, offlineFlag, lockedFlag, formatFlag, manifestFlag)
    private val buildTargetFlags = buildFlags + listOf(targetFlag, wasmLibFlag)

    val commands: List<Command> = listOf(
        Command(
            "new", "Create a new project in a new directory", "<NAME>",
            listOf(
                Flag("lib", "Create a library (source package) instead of an app"),
                templateFlag,
                Flag("strict", "Start in Strict memory mode (`memory = \"strict\"`)"),
                Flag("no-git", "Do not run `git init`"),
            ),
            "Creates <NAME>/ with mini.toml, src/, tests/, .gitignore and a README.",
            listOf("mini new hello", "mini new geometry --lib", "mini new payments --strict", "mini new mathlib --template wasm-lib"),
            minPositionals = 1, maxPositionals = 1,
        ),
        Command(
            "init", "Turn the current directory into a project", "",
            listOf(
                Flag("name", "Package name (default: the directory name)", value = "<NAME>"),
                Flag("lib", "Create a library instead of an app"),
                templateFlag,
                Flag("strict", "Start in Strict memory mode"),
                Flag("no-git", "Do not run `git init`"),
            ),
            "Existing files are never overwritten.",
            listOf("mini init", "mini init --lib --name geometry"),
        ),
        Command(
            "build", "Compile the project to native code (or to WebAssembly for `kind = \"wasm-lib\"`)", "",
            buildTargetFlags,
            "Compiles src/ of the selected packages together with their dependencies (and the stdlib) with minic.\n" +
                "Nothing is rebuilt when no source, manifest or dependency changed.\n" +
                "A `wasm-lib` package (or `--target wasm32 --lib`) becomes build/wasm/<name>.wasm plus <name>.mjs and <name>.d.ts, the JavaScript glue\n" +
                "for its @WasmExport functions. That needs clang with the wasm32 target, wasm-ld and the wasm32 runtime (see `mini doctor`).",
            listOf("mini build", "mini build --release", "mini build --strict -Werror", "mini build -p server", "mini build --target wasm32 --lib"),
        ),
        Command(
            "run", "Build and run the app", "[-- ARGS...]",
            buildFlags,
            "Everything after `--` is passed to the program. The exit code of the program is the exit code of `mini run`.",
            listOf("mini run", "mini run --release -- --port 8080"), passthrough = true,
        ),
        Command(
            "check", "Type-check and run the memory checker, without producing a binary", "",
            listOf(pkgFlag, memFlag, werrorFlag, offlineFlag, lockedFlag, formatFlag, manifestFlag),
            "Runs the compiler frontend and the Strict-mode ownership checker (diagnostics only, no C compiler).",
            listOf("mini check", "mini check --strict -Werror"),
        ),
        Command(
            "package", "Build the distributable library file (.mlib) of a library", "",
            listOf(pkgFlag, memFlag, werrorFlag, offlineFlag, lockedFlag, formatFlag, manifestFlag),
            "Builds the library (and the libraries it depends on) and writes build/package/<name>-<version>.mlib: the API stubs and the\n" +
                "target-independent code of the library in one file (docs/mlib.md). The file works natively, in the JIT and in the browser;\n" +
                "a program that uses it needs the libraries it was built against (listed in its header) next to it.",
            listOf("mini package", "mini package -p geometry"),
        ),
        Command(
            "test", "Run the @Test functions of the project", "[FILTER]",
            listOf(
                pkgFlag, backendFlag, memFlag, werrorFlag, offlineFlag, lockedFlag, manifestFlag,
                Flag("list", "List the tests without building or running them"),
                Flag("nocapture", "Show the output of passing tests too"),
                Flag("fail-fast", "Stop after the first failing test"),
                Flag("release", "Build the tests optimised"),
            ),
            "Finds top-level `@Test fun name()` functions in tests/ and src/, builds one test binary and runs every test\n" +
                "in its own process (so a crash only fails that test). FILTER selects tests whose name contains it.\n" +
                "Assertions come from `import kotlin.test.*` (assertEquals, assertTrue, assertFailsWith, ...).\n" +
                "Arguments after `--` are passed to every test process.",
            listOf("mini test", "mini test parse", "mini test --nocapture", "mini test --list"),
            passthrough = true, maxPositionals = 1,
        ),
        Command("clean", "Remove the build directory", "", listOf(manifestFlag), "Deletes build/ (the lock file and the git cache are kept)."),
        Command(
            "add", "Add a dependency to mini.toml", "[NAME]",
            listOf(
                Flag("path", "Path of a local library", value = "<PATH>"),
                Flag("git", "URL of a git repository", value = "<URL>"),
                Flag("tag", "Git tag to use", value = "<TAG>"),
                Flag("rev", "Git revision to use", value = "<REV>"),
                Flag("branch", "Git branch to track", value = "<BRANCH>"),
                pkgFlag, offlineFlag, manifestFlag,
            ),
            "NAME may be left out: it is then read from the library's own mini.toml.",
            listOf("mini add geometry --path ../geometry", "mini add --git https://github.com/me/json --tag v1.2.0"),
            maxPositionals = 1,
        ),
        Command("remove", "Remove a dependency from mini.toml", "<NAME>", listOf(pkgFlag, manifestFlag), "", listOf("mini remove geometry"), minPositionals = 1, maxPositionals = 1),
        Command(
            "update", "Move the pins of git dependencies in mini.lock to the newest commits", "[NAME...]",
            listOf(manifestFlag, offlineFlag),
            "Without arguments every git dependency is updated. Tags and revisions do not move; branches and the default branch do.",
            maxPositionals = 99,
        ),
        Command("tree", "Print the dependency tree", "", listOf(pkgFlag, offlineFlag, lockedFlag, manifestFlag)),
        Command(
            "explain", "Explain a diagnostic code (e.g. M0102)", "[CODE]",
            listOf(Flag("list", "List all codes")),
            "Prints the description from the memory-model reference (docs/memory-model.md, section 7).",
            listOf("mini explain M0102", "mini explain --list"), maxPositionals = 1,
        ),
        Command("doctor", "Check the toolchain (compiler, C compiler, git, libuv) and suggest fixes", "", emptyList()),
        Command("repl", "Start the persistent LLVM ORC REPL", "", emptyList(),
            "Starts an in-process LLVM ORC session. Enter a declaration or expression on each line; use :help or :quit.",
            listOf("mini repl")),
        Command(
            "completions", "Print a shell completion script", "<SHELL>",
            emptyList(),
            "SHELL is bash, zsh or fish.",
            listOf("mini completions bash > ~/.local/share/bash-completion/completions/mini", "mini completions zsh > ~/.zfunc/_mini", "mini completions fish > ~/.config/fish/completions/mini.fish"),
            minPositionals = 1, maxPositionals = 1,
        ),
        Command("help", "Show help for a command", "[COMMAND]", emptyList(), maxPositionals = 1),
    )

    fun find(name: String): Command? = commands.firstOrNull { it.name == name }

    /** Parses [args] (without the program name). Global flags may appear anywhere. */
    fun parse(args: List<String>): Parsed {
        var cmd: Command? = null
        val flags = LinkedHashMap<String, MutableList<String>>()
        val pos = ArrayList<String>()
        val rest = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                if (cmd == null) throw UsageError("`--` must come after a command", "e.g. `mini run -- --my-flag`")
                if (!cmd.passthrough) throw UsageError("`mini ${cmd.name}` does not take arguments after `--`", "see `mini help ${cmd.name}`")
                rest.addAll(args.subList(i + 1, args.size))
                break
            }
            if (a.startsWith("--") || (a.startsWith("-") && a.length > 1)) {
                val isLong = a.startsWith("--")
                val body = if (isLong) a.substring(2) else a.substring(1)
                var name = if (isLong) body.substringBefore('=') else body.take(1)
                var inline: String? = if (isLong) (if (body.contains('=')) body.substringAfter('=') else null) else body.drop(1).takeIf { it.isNotEmpty() }
                val candidates = (cmd?.flags ?: emptyList()) + global
                // `-Werror` is the one long-ish single-dash flag (as in clang/kotlinc)
                if (!isLong && body == "Werror") { name = "Werror"; inline = null }
                val flag = candidates.firstOrNull { if (isLong || name == "Werror") it.long == name else it.short == name[0] }
                if (flag == null) {
                    val spelled = if (isLong) "--$name" else "-$name"
                    val all = candidates.map { "--" + it.long }
                    val sug = if (isLong) suggest("--$name", all) else null
                    val where = if (cmd != null) " for `mini ${cmd.name}`" else ""
                    throw UsageError("unknown option `$spelled`$where", sug?.let { "did you mean `$it`?" } ?: ("see `mini help" + (cmd?.let { " " + it.name } ?: "") + "`"))
                }
                if (flag.value != null) {
                    val v = inline ?: run {
                        if (i + 1 >= args.size || (args[i + 1].startsWith("-") && args[i + 1].length > 1 && flag.choices == null)) {
                            throw UsageError("option `--${flag.long}` needs a value ${flag.value}", "write `--${flag.long} ${flag.value}` or `--${flag.long}=...`")
                        }
                        args[++i]
                    }
                    if (flag.choices != null && v !in flag.choices) {
                        throw UsageError("invalid value `$v` for `--${flag.long}`", suggest(v, flag.choices)?.let { "did you mean `$it`?" } ?: "expected one of: ${flag.choices.joinToString(", ")}")
                    }
                    val l = flags.getOrPut(flag.long) { ArrayList() }
                    if (l.isNotEmpty() && !flag.repeatable) l.clear()
                    l += v
                } else {
                    if (inline != null && isLong) throw UsageError("option `--${flag.long}` does not take a value")
                    flags.getOrPut(flag.long) { ArrayList() }
                    if (!isLong && inline != null) throw UsageError("combined short flags like `-${body}` are not supported", "write them separately: ${body.map { "-$it" }.joinToString(" ")}")
                }
            } else if (cmd == null) {
                cmd = find(a) ?: throw UsageError(
                    "unknown command `$a`",
                    suggest(a, commands.map { it.name })?.let { "did you mean `$it`?" } ?: "run `mini help` for the list of commands",
                )
            } else pos += a
            i++
        }
        if (cmd == null) {
            // only global flags: `mini --version`, `mini --help`, or nothing
            return Parsed(find("help")!!, flags, emptyList(), emptyList())
        }
        if (pos.size > cmd.maxPositionals) {
            val extra = pos[cmd.maxPositionals]
            val hint = if (cmd.maxPositionals == 0) "`mini ${cmd.name}` takes no arguments" + (if (cmd.passthrough) "; pass program arguments after `--`" else "")
            else "usage: mini ${cmd.name} ${cmd.args}".trim()
            throw UsageError("unexpected argument `$extra`", hint)
        }
        if (pos.size < cmd.minPositionals && !flags.containsKey("help")) {
            throw UsageError("`mini ${cmd.name}` needs ${cmd.args}", "usage: mini ${cmd.name} ${cmd.args}".trim() + "; see `mini help ${cmd.name}`")
        }
        return Parsed(cmd, flags, pos, rest)
    }

    // ---- help ------------------------------------------------------------------------------------------------------

    fun topHelp(s: Style, version: String): String {
        val sb = StringBuilder()
        sb.append(s.bold("mini $version")).append(" - the Kotlin/Mini project tool\n\n")
        sb.append(s.green("Usage:", bold = true)).append(" mini [OPTIONS] <COMMAND>\n\n")
        sb.append(s.green("Commands:", bold = true)).append('\n')
        for (c in commands) sb.append("  ").append(s.cyan(c.name.padEnd(12), bold = true)).append(c.summary).append('\n')
        sb.append('\n').append(s.green("Options:", bold = true)).append('\n')
        appendFlags(sb, global, s)
        sb.append("\nRun ").append(s.bold("mini help <command>")).append(" for details, or start with ").append(s.bold("mini new hello")).append(".\n")
        return sb.toString()
    }

    fun commandHelp(c: Command, s: Style): String {
        val sb = StringBuilder()
        sb.append(c.summary).append("\n\n")
        sb.append(s.green("Usage:", bold = true)).append(" mini ${c.name}").append(if (c.flags.isNotEmpty()) " [OPTIONS]" else "")
        if (c.args.isNotEmpty()) sb.append(' ').append(c.args)
        sb.append("\n")
        if (c.details.isNotEmpty()) sb.append('\n').append(c.details).append('\n')
        if (c.flags.isNotEmpty()) { sb.append('\n').append(s.green("Options:", bold = true)).append('\n'); appendFlags(sb, c.flags, s) }
        sb.append('\n').append(s.green("Global options:", bold = true)).append('\n')
        appendFlags(sb, global.filter { it.long != "version" }, s)
        if (c.examples.isNotEmpty()) {
            sb.append('\n').append(s.green("Examples:", bold = true)).append('\n')
            for (e in c.examples) sb.append("  ").append(e).append('\n')
        }
        return sb.toString()
    }

    private fun appendFlags(sb: StringBuilder, flags: List<Flag>, s: Style) {
        val labels = flags.map { f ->
            val short = if (f.short != null) "-${f.short}, " else "    "
            val dash = if (f.long == "Werror") "-Werror" else "--${f.long}"
            short + dash + (if (f.value != null) " ${f.value}" else "")
        }
        val w = (labels.fold(0) { m, l -> if (l.length > m) l.length else m }) + 2
        for ((i, f) in flags.withIndex()) sb.append("  ").append(s.cyan(labels[i].padEnd(w))).append(f.help).append('\n')
    }
}
