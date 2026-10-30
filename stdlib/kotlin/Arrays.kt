/*
 * Mini stdlib: array helpers on top of the builtin arrays (kotlin.Array, IntArray, ...), which are
 * runtime objects (MkArray). `for (i in array.indices)` is pattern-matched by the backend.
 */
package kotlin

@Suppress("UNCHECKED_CAST")
public fun <T> arrayOf(vararg elements: T): Array<T> = elements as Array<T>

public fun intArrayOf(vararg elements: Int): IntArray = elements

public fun longArrayOf(vararg elements: Long): LongArray = elements

public fun doubleArrayOf(vararg elements: Double): DoubleArray = elements

public fun floatArrayOf(vararg elements: Float): FloatArray = elements

public fun booleanArrayOf(vararg elements: Boolean): BooleanArray = elements

public fun charArrayOf(vararg elements: Char): CharArray = elements

/** A new array of `size` null slots; implemented by the backend (a runtime array allocation). */
public external fun <T> arrayOfNulls(size: Int): Array<T?>

@Suppress("UNCHECKED_CAST")
public fun <T> emptyArray(): Array<T> = arrayOfNulls<T>(0) as Array<T>

/** `lateinit` check: `this::x.isInitialized`. Recognised by the backend (it never calls this body). */
public val kotlin.reflect.KProperty0<*>.isInitialized: Boolean
    get() = true

public val Array<*>.lastIndex: Int
    get() = size - 1

public val IntArray.lastIndex: Int
    get() = size - 1

public val LongArray.lastIndex: Int
    get() = size - 1

public val DoubleArray.lastIndex: Int
    get() = size - 1

public val FloatArray.lastIndex: Int
    get() = size - 1

public val BooleanArray.lastIndex: Int
    get() = size - 1

public val CharArray.lastIndex: Int
    get() = size - 1

public fun <T> Array<out T>.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i].toString())
    }
    sb.append(']')
    return sb.toString()
}

public fun IntArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

public fun LongArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

public fun DoubleArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

public fun FloatArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

public fun BooleanArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

public fun CharArray.contentToString(): String {
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until size) {
        if (i > 0) sb.append(", ")
        sb.append(this[i])
    }
    sb.append(']')
    return sb.toString()
}

/** `string + x` where the receiver may be null (prints "null"), like kotlin.String?.plus. */
public operator fun String?.plus(other: Any?): String = (this ?: "null") + other.toString()
