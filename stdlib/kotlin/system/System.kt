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
