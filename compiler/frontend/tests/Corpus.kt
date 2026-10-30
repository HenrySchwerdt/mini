import mini.io.isDirectory
import mini.io.walkFiles

/**
 * All Kotlin sources of the repository: compiler modules, testdata, stdlib, stdlib-test, examples, selfhost.
 * `mini test` runs a test in the directory of its package (compiler/frontend), so the repository root is `../..`.
 */
object Corpus {
    const val repoRoot = "../.."

    /** Paths (starting with [repoRoot]) of all `.kt` files, sorted. */
    val files: List<String> by lazy {
        val roots = listOf("compiler", "stdlib", "stdlib-test", "examples", "runtime", "docs", "tools")
        val out = ArrayList<String>()
        for (r in roots) {
            val dir = "$repoRoot/$r"
            if (!isDirectory(dir)) continue
            out.addAll(walkFiles(dir, ".kt"))
        }
        out.sorted()
    }

    fun rel(f: String): String = f.removePrefix("$repoRoot/")
}
