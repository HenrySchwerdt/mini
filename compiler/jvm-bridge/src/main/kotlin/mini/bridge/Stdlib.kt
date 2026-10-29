package mini.bridge

import java.io.File

/**
 * The Mini standard library is Kotlin source (`std/kotlin-mini`, see docs/stdlib.md). `minic build`
 * compiles it together with the user files through K2 so every stdlib function has IR.
 */
object Stdlib {
    const val RELATIVE_DIR = "std/kotlin-mini"

    /** `--stdlib <dir>` wins, then `<repoRoot>/std/kotlin-mini`; null if neither exists. */
    fun findDir(explicit: File?, repoRoot: File?): File? {
        val d = explicit ?: repoRoot?.let { File(it, RELATIVE_DIR) }
        return d?.takeIf { it.isDirectory }
    }

    fun sources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
}
