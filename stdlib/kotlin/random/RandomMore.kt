/*
 * Mini stdlib: the rest of kotlin.random (byte arrays, ranges, unsigned numbers); the algorithms follow kotlin-stdlib so that a
 * seeded Random produces the same numbers as on the JVM.
 */
package kotlin.random

public fun Random.nextInt(range: IntRange): Int {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot get random in empty range: " + range)
    return if (range.last < Int.MAX_VALUE) nextInt(range.first, range.last + 1)
    else if (range.first > Int.MIN_VALUE) nextInt(range.first - 1, range.last) + 1
    else nextInt()
}

public fun Random.nextLong(range: LongRange): Long {
    if (range.isEmpty()) throw IllegalArgumentException("Cannot get random in empty range: " + range)
    return if (range.last < Long.MAX_VALUE) nextLong(range.first, range.last + 1L)
    else if (range.first > Long.MIN_VALUE) nextLong(range.first - 1L, range.last) + 1L
    else nextLong()
}

public fun Random.nextUInt(): UInt = nextInt().toUInt()

public fun Random.nextUInt(until: UInt): UInt = nextUInt(0u, until)

public fun Random.nextUInt(from: UInt, until: UInt): UInt {
    if (until <= from) throw IllegalArgumentException("Random range is empty: [" + from + ", " + until + ").")
    val signedFrom = from.toInt() xor Int.MIN_VALUE
    val signedUntil = until.toInt() xor Int.MIN_VALUE
    val signedResult = nextInt(signedFrom, signedUntil) xor Int.MIN_VALUE
    return signedResult.toUInt()
}

public fun Random.nextULong(): ULong = nextLong().toULong()

public fun Random.nextULong(until: ULong): ULong = nextULong(0uL, until)

public fun Random.nextULong(from: ULong, until: ULong): ULong {
    if (until <= from) throw IllegalArgumentException("Random range is empty: [" + from + ", " + until + ").")
    val signedFrom = from.toLong() xor Long.MIN_VALUE
    val signedUntil = until.toLong() xor Long.MIN_VALUE
    val signedResult = nextLong(signedFrom, signedUntil) xor Long.MIN_VALUE
    return signedResult.toULong()
}
