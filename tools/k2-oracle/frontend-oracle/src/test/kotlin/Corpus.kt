import java.io.File

/**
 * All Kotlin sources of the repository: compiler modules, testdata, stdlib, stdlib-test, examples, selfhost.
 * Programs that use Mini language extensions K2 cannot parse (e.g. the `@` operator) carry a marker file `NAME.nok2`
 * next to `NAME.kt` and are left out here (the K2 oracle cannot compare them); see [isK2Parsable].
 */
object Corpus {
    val repoRoot = File(System.getProperty("mini.repoRoot") ?: error("mini.repoRoot not set"))

    val files: List<File> by lazy {
        val roots = listOf("compiler", "stdlib", "stdlib-test", "examples", "runtime", "docs")
        val out = ArrayList<File>()
        for (r in roots) {
            val dir = File(repoRoot, r)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
                .filter { it.isFile && it.extension == "kt" && isK2Parsable(it) }
                .forEach { out.add(it) }
        }
        out.sortedBy { it.path }
    }

    /** false for `NAME.kt` with a marker file `NAME.nok2` beside it */
    fun isK2Parsable(f: File): Boolean = !File(f.path.removeSuffix(".kt") + ".nok2").exists()

    fun rel(f: File): String = f.relativeTo(repoRoot).path
}
