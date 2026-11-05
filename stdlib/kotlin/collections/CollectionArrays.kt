/*
 * Conversions from collections to arrays.
 */
package kotlin.collections

@Suppress("UNCHECKED_CAST")
public fun <T> Collection<T>.toTypedArray(): Array<T> {
    val r = arrayOfNulls<T>(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r as Array<T>
}

public fun Collection<Int>.toIntArray(): IntArray {
    val r = IntArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}

public fun Collection<Long>.toLongArray(): LongArray {
    val r = LongArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}

public fun Collection<Double>.toDoubleArray(): DoubleArray {
    val r = DoubleArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}

public fun Collection<Float>.toFloatArray(): FloatArray {
    val r = FloatArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}

public fun Collection<Char>.toCharArray(): CharArray {
    val r = CharArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}

public fun Collection<Boolean>.toBooleanArray(): BooleanArray {
    val r = BooleanArray(size)
    var i = 0
    for (e in this) {
        r[i] = e
        i++
    }
    return r
}
