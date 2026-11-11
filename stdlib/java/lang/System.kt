/*
 * The part of java.lang.System and java.lang.Runtime that portable programs use: the clocks, the line separator, exit, array copy and the
 * processor count. (No properties, environment or streams: use println / readLine.)
 */
package java.lang

import mini.internal.epochMillis
import mini.internal.sysCpuCount

public object System {
    public fun currentTimeMillis(): Long = epochMillis()
    public fun nanoTime(): Long = mini.internal.nanoTime()
    public fun lineSeparator(): String = "\n"
    public fun exit(status: Int): Nothing {
        mini.internal.exitProcess(status)
        throw IllegalStateException("exit returned")
    }

    public fun arraycopy(src: Any, srcPos: Int, dest: Any, destPos: Int, length: Int) {
        if (srcPos < 0 || destPos < 0 || length < 0) throw IndexOutOfBoundsException("arraycopy: negative index or length")
        when (src) {
            is IntArray -> src.copyInto(dest as IntArray, destPos, srcPos, srcPos + length)
            is LongArray -> src.copyInto(dest as LongArray, destPos, srcPos, srcPos + length)
            is DoubleArray -> src.copyInto(dest as DoubleArray, destPos, srcPos, srcPos + length)
            is FloatArray -> src.copyInto(dest as FloatArray, destPos, srcPos, srcPos + length)
            is ByteArray -> src.copyInto(dest as ByteArray, destPos, srcPos, srcPos + length)
            is ShortArray -> src.copyInto(dest as ShortArray, destPos, srcPos, srcPos + length)
            is CharArray -> src.copyInto(dest as CharArray, destPos, srcPos, srcPos + length)
            is BooleanArray -> src.copyInto(dest as BooleanArray, destPos, srcPos, srcPos + length)
            is Array<*> -> copyRefs<Any?>(src, dest as Array<*>, srcPos, destPos, length)
            else -> throw IllegalArgumentException("arraycopy: not an array")
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> copyRefs(src: Array<*>, dest: Array<*>, srcPos: Int, destPos: Int, length: Int) {
    (src as Array<T>).copyInto(dest as Array<T>, destPos, srcPos, srcPos + length)
}

public class Runtime private constructor() {
    public fun availableProcessors(): Int = sysCpuCount().toInt()

    public companion object {
        private val instance = Runtime()
        public fun getRuntime(): Runtime = instance
    }
}
