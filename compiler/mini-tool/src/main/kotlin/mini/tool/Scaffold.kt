package mini.tool

/** Templates for `mini new` / `mini init` and the textual edits of `mini add` / `mini remove`. */
object Templates {
    fun manifest(name: String, kind: Kind, strict: Boolean): String = buildString {
        append("[package]\n")
        append("name = \"$name\"\n")
        append("version = \"0.1.0\"\n")
        append("kind = \"${kind.text}\"          # \"app\" builds an executable, \"lib\" is a source package for other projects\n")
        append("memory = \"${if (strict) "strict" else "managed"}\"    # \"managed\": reference counting; \"strict\": every allocation needs a proven owner\n")
        append("\n[dependencies]\n")
        append("# geometry = { path = \"../geometry\" }\n")
        append("# json = { git = \"https://github.com/you/json\", tag = \"v1.0.0\" }\n")
    }

    fun mainKt(): String = "fun main() {\n    println(greeting(\"world\"))\n}\n"

    fun greetingKt(): String = "fun greeting(name: String): String = \"Hello, \$name!\"\n"

    fun greetingTest(): String = """import kotlin.test.*

@Test
fun greetsTheWorld() {
    assertEquals("Hello, world!", greeting("world"))
}

@Test
fun greetsByName() {
    assertEquals("Hello, Mini!", greeting("Mini"))
}
"""

    fun libKt(pkg: String): String = "package $pkg\n\n/** Adds two numbers. Replace this with your library. */\nfun add(a: Int, b: Int): Int = a + b\n"

    fun libTest(pkg: String): String = """import kotlin.test.*
import $pkg.*

@Test
fun addsNumbers() {
    assertEquals(5, add(2, 3))
}
"""

    fun gitignore(): String = "/build/\n"

    fun readme(name: String, kind: Kind): String = buildString {
        append("# $name\n\n")
        if (kind == Kind.App) {
            append("A Mini (Kotlin to native) application.\n\n```sh\nmini run        # build and run\nmini test       # run the tests in tests/\nmini build --release\n```\n")
        } else {
            append("A Mini library (source package).\n\n```sh\nmini test       # run the tests in tests/\nmini build      # compile the library to check it\n```\n\n")
            append("Use it from another project:\n\n```toml\n[dependencies]\n$name = { path = \"../$name\" }\n```\n")
        }
        append("\nSee `mini help` for all commands.\n")
    }
}

object ManifestEdit {
    private fun section(lines: List<String>): Pair<Int, Int>? {
        val h = lines.indexOfFirst { isDependenciesHeader(it) }
        if (h < 0) return null
        var end = lines.size
        for (i in h + 1 until lines.size) if (lines[i].trimStart().startsWith("[")) { end = i; break }
        return Pair(h, end)
    }

    private fun keyOf(line: String): String? =
        tomlKeyOf(line)

    /** Adds or replaces `name = <spec>` in `[dependencies]`; returns the new text and whether a line was replaced. */
    fun addDependency(text: String, name: String, spec: String): Pair<String, Boolean> {
        val lines = text.split('\n').toMutableList()
        val line = "$name = $spec"
        val sec = section(lines)
        if (sec == null) {
            val base = text.trimEndChars('\n')
            return Pair("$base\n\n[dependencies]\n$line\n", false)
        }
        val (h, end) = sec
        for (i in h + 1 until end) if (keyOf(lines[i]) == name) { lines[i] = line; return Pair(lines.joinToString("\n"), true) }
        var at = h + 1
        for (i in h + 1 until end) if (keyOf(lines[i]) != null) at = i + 1
        lines.add(at, line)
        return Pair(lines.joinToString("\n"), false)
    }

    fun removeDependency(text: String, name: String): String? {
        val lines = text.split('\n').toMutableList()
        val (h, end) = section(lines) ?: return null
        for (i in h + 1 until end) if (keyOf(lines[i]) == name) { lines.removeAt(i); return lines.joinToString("\n") }
        return null
    }

    fun dependencyNames(text: String): List<String> {
        val lines = text.split('\n')
        val (h, end) = section(lines) ?: return emptyList()
        return (h + 1 until end).mapNotNull { keyOf(lines[it]) }
    }
}
