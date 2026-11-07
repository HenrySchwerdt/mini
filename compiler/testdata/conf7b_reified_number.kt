// a reified T (substituted by a primitive in the expansion) as the receiver of Number members
inline operator fun <reified T : Number> T.plus(other: T): T = when (T::class) {
    Double::class -> (this as Double) + (other as Double)
    Int::class -> (this as Int) + (other as Int)
    Long::class -> (this as Long) + (other as Long)
    else -> TODO()
} as T

inline fun <reified T : Number> sum2(x: T, y: T): Double = (x + y).toDouble()

inline fun <reified T : Number> median(c: Collection<T>): Double {
    val sorted = c.sortedBy { it.toDouble() }
    val size = c.size
    return if (size % 2 == 0 || size == 1) sorted[size / 2].toDouble()
    else (sorted[size / 2] + sorted[size / 2 + 1]).toDouble()
}

fun main() {
    println(sum2(1L, 2L))
    println(sum2(1, 2))
    println(sum2(1.5, 2.0))
    println(median(mutableListOf<Long>(1L)))
    println(median(listOf(3L, 1L, 2L, 7L)))
}
