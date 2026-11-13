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

fun fileReadBytes(path: String): ByteArray? = try {
    java.io.File(path).readBytes()
} catch (e: java.io.IOException) {
    null
}

fun fileWriteBytes(path: String, bytes: ByteArray, append: Boolean): Boolean = try {
    if (append) java.io.File(path).appendBytes(bytes) else java.io.File(path).writeBytes(bytes)
    true
} catch (e: java.io.IOException) {
    false
}

fun fileStat(path: String, what: Int): Long {
    val f = java.io.File(path)
    if (!f.exists()) return -1L
    return if (what == 0) f.length() else f.lastModified()
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
