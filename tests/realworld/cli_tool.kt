// A command line tool: option parsing (flags, valued options, short groups, subcommands, positional arguments) and help text.

class UsageError(message: String) : Exception(message)

enum class Kind { FLAG, VALUE, MULTI }

class OptionSpec(val long: String, val short: Char?, val kind: Kind, val description: String, val default: String? = null)

class Command(val name: String, val summary: String, val options: List<OptionSpec>, val positional: List<String>, val action: (Parsed) -> Int)

class Parsed(val command: Command) {
    val flags = HashSet<String>()
    val values = HashMap<String, String>()
    val multi = HashMap<String, MutableList<String>>()
    val positional = ArrayList<String>()

    fun flag(name: String) = name in flags
    fun value(name: String): String? = values[name] ?: command.options.firstOrNull { it.long == name }?.default
    fun int(name: String): Int = value(name)?.toIntOrNull() ?: throw UsageError("--$name needs a number, got '${value(name)}'")
    fun all(name: String): List<String> = multi[name] ?: emptyList()
}

class Cli(private val program: String, private val commands: List<Command>) {
    fun help(): String = buildString {
        appendLine("usage: $program <command> [options]")
        appendLine()
        appendLine("commands:")
        for (c in commands) appendLine("  ${c.name.padEnd(8)} ${c.summary}")
    }

    fun commandHelp(c: Command): String = buildString {
        append("usage: $program ${c.name} [options]")
        for (p in c.positional) append(" <$p>")
        appendLine()
        for (o in c.options) {
            val short = o.short?.let { "-$it, " } ?: "    "
            val arg = when (o.kind) {
                Kind.FLAG -> ""
                else -> " <value>"
            }
            val def = o.default?.let { " (default: $it)" } ?: ""
            appendLine("  $short--${o.long}$arg  ${o.description}$def")
        }
    }

    fun parse(args: List<String>): Parsed {
        if (args.isEmpty()) throw UsageError("missing command")
        val cmd = commands.firstOrNull { it.name == args[0] } ?: throw UsageError("unknown command '${args[0]}'")
        val parsed = Parsed(cmd)
        var i = 1
        var onlyPositional = false
        fun spec(long: String?, short: Char?): OptionSpec =
            cmd.options.firstOrNull { (long != null && it.long == long) || (short != null && it.short == short) }
                ?: throw UsageError("unknown option ${long?.let { "--$it" } ?: "-$short"}")
        fun apply(o: OptionSpec, inline: String?, rest: () -> String?) {
            when (o.kind) {
                Kind.FLAG -> {
                    if (inline != null) throw UsageError("--${o.long} takes no value")
                    parsed.flags.add(o.long)
                }
                Kind.VALUE -> parsed.values[o.long] = inline ?: rest() ?: throw UsageError("--${o.long} needs a value")
                Kind.MULTI -> parsed.multi.getOrPut(o.long) { ArrayList() }.add(inline ?: rest() ?: throw UsageError("--${o.long} needs a value"))
            }
        }
        while (i < args.size) {
            val a = args[i]
            when {
                onlyPositional || a == "-" || !a.startsWith("-") -> parsed.positional.add(a)
                a == "--" -> onlyPositional = true
                a.startsWith("--") -> {
                    val name = a.substring(2).substringBefore('=')
                    val inline = if ('=' in a) a.substringAfter('=') else null
                    apply(spec(name, null), inline) { args.getOrNull(++i) }
                }
                else -> {
                    val group = a.substring(1)
                    for ((k, ch) in group.withIndex()) {
                        val o = spec(null, ch)
                        if (o.kind == Kind.FLAG) apply(o, null) { null }
                        else {
                            val tail = group.substring(k + 1)
                            apply(o, tail.ifEmpty { null }) { args.getOrNull(++i) }
                            break
                        }
                    }
                }
            }
            i++
        }
        if (parsed.positional.size < cmd.positional.size) throw UsageError("expected ${cmd.positional.joinToString(" ") { "<$it>" }}")
        return parsed
    }

    fun run(args: List<String>): Int {
        if (args.firstOrNull() in listOf("-h", "--help", "help")) {
            println(help().trimEnd())
            return 0
        }
        return try {
            val p = parse(args)
            if (p.flag("help")) {
                print(commandHelp(p.command))
                0
            } else p.command.action(p)
        } catch (e: UsageError) {
            println("error: ${e.message}")
            2
        }
    }
}

fun wordCount(text: String): Triple<Int, Int, Int> = Triple(text.lines().size, text.split(Regex("\\s+")).count { it.isNotEmpty() }, text.length)

fun main() {
    val common = listOf(OptionSpec("help", 'h', Kind.FLAG, "show help"), OptionSpec("verbose", 'v', Kind.FLAG, "more output"))
    val commands = listOf(
        Command("greet", "print a greeting", common + listOf(
            OptionSpec("name", 'n', Kind.VALUE, "who to greet", "world"),
            OptionSpec("times", 't', Kind.VALUE, "repetitions", "1"),
            OptionSpec("shout", 's', Kind.FLAG, "upper case"),
        ), emptyList()) { p ->
            val msg = "hello, ${p.value("name")}!".let { if (p.flag("shout")) it.uppercase() else it }
            repeat(p.int("times")) { println(msg) }
            if (p.flag("verbose")) println("(greeted ${p.int("times")} time(s))")
            0
        },
        Command("count", "count lines, words and characters", common, listOf("text")) { p ->
            val (l, w, c) = wordCount(p.positional.joinToString(" "))
            println("$l lines, $w words, $c chars")
            0
        },
        Command("sum", "sum the numbers given with -x", common + OptionSpec("x", 'x', Kind.MULTI, "a number"), emptyList()) { p ->
            val nums = p.all("x").map { it.toIntOrNull() ?: throw UsageError("not a number: $it") }
            println("sum of ${nums.size} numbers = ${nums.sum()}")
            0
        },
    )
    val cli = Cli("tool", commands)
    val invocations = listOf(
        listOf("help"),
        listOf("greet"),
        listOf("greet", "--name", "Kotlin", "-t", "2"),
        listOf("greet", "-sv", "--name=mini", "--times=3"),
        listOf("greet", "--help"),
        listOf("count", "one two", "three"),
        listOf("count"),
        listOf("sum", "-x", "1", "-x2", "-x", "39"),
        listOf("sum", "-x", "abc"),
        listOf("greet", "--times", "many"),
        listOf("greet", "--bogus"),
        listOf("frobnicate"),
        listOf("count", "--", "--not-an-option", "-x"),
        emptyList(),
    )
    for (inv in invocations) {
        println("$ tool ${inv.joinToString(" ")}".trimEnd())
        val code = cli.run(inv)
        if (code != 0) println("(exit code $code)")
    }
}
