package mini.internal

/*
 * JVM implementation of mini/internal/B1Intrinsics.kt. Besides the stdlib unit tests, the Gradle modules
 * of tools/k2-oracle compile this file so that the driver also runs on the JVM.
 * Signatures must match the native file (B1IntrinsicsSyncTest).
 */

fun fileReadText(path: String): String? = try {
    java.io.File(path).readText(Charsets.UTF_8)
} catch (e: java.io.IOException) {
    null
}

fun fileWriteText(path: String, text: String): Boolean = try {
    java.io.File(path).writeText(text, Charsets.UTF_8)
    true
} catch (e: java.io.IOException) {
    false
}

fun fileKind(path: String): Int {
    val f = java.io.File(path)
    return if (f.isDirectory) 2 else if (f.isFile) 1 else 0
}

fun fileListDir(path: String): String? {
    val names = java.io.File(path).list() ?: return null
    return names.sorted().joinToString("\n")
}

fun fileMkdirs(path: String): Boolean = java.io.File(path).let { it.mkdirs() || it.isDirectory }

fun fileDelete(path: String): Boolean = java.io.File(path).delete()

fun stderrWrite(text: String) {
    System.out.flush()
    System.err.print(text)
    System.err.flush()
}

fun exitProcess(status: Int) {
    System.out.flush()
    System.exit(status)
}
