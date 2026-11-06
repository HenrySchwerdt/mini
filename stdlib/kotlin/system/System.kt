/*
 * Mini stdlib: kotlin.system (measureTimeMillis, measureNanoTime, exitProcess).
 */
package kotlin.system

public inline fun measureTimeMillis(block: () -> Unit): Long {
    val start = mini.internal.epochMillis()
    block()
    return mini.internal.epochMillis() - start
}

public inline fun measureNanoTime(block: () -> Unit): Long {
    val start = mini.internal.nanoTime()
    block()
    return mini.internal.nanoTime() - start
}

public fun exitProcess(status: Int): Nothing {
    mini.internal.exitProcess(status)
    throw IllegalStateException("exitProcess returned")
}

/** Milliseconds since the Unix epoch (Kotlin/Native `kotlin.system.getTimeMillis`). */
public fun getTimeMillis(): Long = mini.internal.epochMillis()

/** A monotonic clock in nanoseconds (`kotlin.system.getTimeNanos`). */
public fun getTimeNanos(): Long = mini.internal.nanoTime()

/** A monotonic clock in microseconds (`kotlin.system.getTimeMicros`). */
public fun getTimeMicros(): Long = mini.internal.nanoTime() / 1000L
