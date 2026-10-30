package mini.tool

/** ANSI styling that is a no-op when [enabled] is false. */
class Style(val enabled: Boolean) {
    private fun wrap(code: String, s: String): String = if (enabled) "\u001b[${code}m$s\u001b[0m" else s
    fun bold(s: String) = wrap("1", s)
    fun dim(s: String) = wrap("2", s)
    fun red(s: String, bold: Boolean = false) = wrap(if (bold) "1;31" else "31", s)
    fun green(s: String, bold: Boolean = false) = wrap(if (bold) "1;32" else "32", s)
    fun yellow(s: String, bold: Boolean = false) = wrap(if (bold) "1;33" else "33", s)
    fun blue(s: String, bold: Boolean = false) = wrap(if (bold) "1;34" else "34", s)
    fun cyan(s: String, bold: Boolean = false) = wrap(if (bold) "1;36" else "36", s)

    companion object {
        val Plain = Style(false)
    }
}

enum class ColorMode { Auto, Always, Never }
enum class Verbosity { Quiet, Normal, Verbose }

/**
 * All user-visible output goes through here: cargo-style status lines on stderr, results on stdout.
 * Colours follow `--color`, then `NO_COLOR` / `CLICOLOR_FORCE`, then whether the stream is a terminal.
 */
class Console(val platform: Platform, colorMode: ColorMode, val verbosity: Verbosity) {
    val out: Style
    val err: Style

    init {
        val noColor = !platform.env("NO_COLOR").isNullOrEmpty()
        val force = !platform.env("CLICOLOR_FORCE").isNullOrEmpty() && platform.env("CLICOLOR_FORCE") != "0"
        out = Style(decide(colorMode, noColor, force, Stream.Stdout))
        err = Style(decide(colorMode, noColor, force, Stream.Stderr))
    }

    private fun decide(colorMode: ColorMode, noColor: Boolean, force: Boolean, s: Stream): Boolean = when (colorMode) {
        ColorMode.Always -> true
        ColorMode.Never -> false
        ColorMode.Auto -> !noColor && (force || (platform.isTerminal(s) && platform.env("TERM") != "dumb"))
    }

    val quiet get() = verbosity == Verbosity.Quiet
    val verbose get() = verbosity == Verbosity.Verbose

    fun print(text: String) = platform.write(Stream.Stdout, text)
    fun println(text: String = "") = platform.write(Stream.Stdout, text + "\n")
    fun eprint(text: String) = platform.write(Stream.Stderr, text)
    fun eprintln(text: String = "") = platform.write(Stream.Stderr, text + "\n")

    /** `   Compiling hello v0.1.0 (path)`: right-aligned bold green verb. Hidden by `--quiet`. */
    fun status(verb: String, message: String) {
        if (quiet) return
        eprintln(err.green(verb.padStart(12), bold = true) + " " + message)
    }

    /** Like [status] but for things that went wrong without failing (yellow). */
    fun statusWarn(verb: String, message: String) {
        if (quiet) return
        eprintln(err.yellow(verb.padStart(12), bold = true) + " " + message)
    }

    fun note(message: String) {
        if (quiet) return
        eprintln(err.cyan("note:", bold = true) + " " + message)
    }

    fun warn(message: String, help: String? = null) {
        eprintln(err.yellow("warning:", bold = true) + " " + err.bold(message))
        if (help != null) eprintln("  " + err.cyan("help:", bold = true) + " " + help)
    }

    fun error(message: String, help: String? = null) {
        eprintln(err.red("error:", bold = true) + " " + err.bold(message))
        if (help != null) for (h in help.split('\n')) eprintln("  " + err.cyan("help:", bold = true) + " " + h)
    }

    /** `--verbose` only: shows the command lines mini runs. */
    fun trace(message: String) {
        if (verbose) eprintln(err.dim("     Running " + message))
    }
}

/** `1.2s`, `85ms`, `2m 03s`. */
fun formatDuration(millis: Long): String = when {
    millis < 1000 -> "${millis}ms"
    millis < 60_000 -> "${millis / 1000}.${((millis % 1000) / 100)}s"
    else -> "${millis / 60_000}m ${((millis % 60_000) / 1000).toString().padStart(2, '0')}s"
}

/** Levenshtein-based "did you mean": the closest of [candidates] within a small distance, or null. */
fun suggest(input: String, candidates: Collection<String>): String? {
    var best: String? = null
    var bestD = Int.MAX_VALUE
    for (c in candidates) {
        val d = editDistance(input, c)
        if (d < bestD) { bestD = d; best = c }
    }
    val limit = maxOf(1, minOf(3, input.length / 3 + 1))
    // also accept prefixes ("bui" -> "build")
    val prefix = candidates.firstOrNull { it.startsWith(input) && input.length >= 2 }
    return if (bestD <= limit) best else prefix
}

fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}
