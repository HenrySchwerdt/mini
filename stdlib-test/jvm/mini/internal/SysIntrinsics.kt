package mini.internal

/*
 * JVM implementation of mini/internal/SysIntrinsics.kt (standard input and clocks). Signatures must match the native file.
 */

fun stdinReadLine(): String? = readlnOrNull()

fun nanoTime(): Long = System.nanoTime()

fun epochMillis(): Long = System.currentTimeMillis()

fun doubleToBits(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)

fun floatToBits(v: Float): Int = java.lang.Float.floatToRawIntBits(v)
