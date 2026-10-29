import java.io.File

/** All Kotlin sources of the repository: compiler modules, testdata, stdlib, stdlib-test, examples, selfhost. */
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
                .filter { it.isFile && it.extension == "kt" }
                .forEach { out.add(it) }
        }
        out.sortedBy { it.path }
    }

    fun rel(f: File): String = f.relativeTo(repoRoot).path
}
