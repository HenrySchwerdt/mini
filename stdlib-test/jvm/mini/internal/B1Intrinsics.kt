package mini.internal

/*
 * JVM implementation of mini/internal/B1Intrinsics.kt. Besides the stdlib unit tests, the Gradle module
 * compiler/native-driver compiles this file so that the driver also runs on the JVM.
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

fun stderrWrite(text: String) {
    System.out.flush()
    System.err.print(text)
    System.err.flush()
}

fun exitProcess(status: Int) {
    System.out.flush()
    System.exit(status)
}
