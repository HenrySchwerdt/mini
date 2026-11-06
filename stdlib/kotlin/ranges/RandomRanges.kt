package kotlin.ranges

import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.random.nextLong
import kotlin.random.nextUInt
import kotlin.random.nextULong

public fun IntRange.random(random: Random): Int {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextInt(start, endInclusive + 1)
}

public fun IntRange.random(): Int = random(Random.Default)

public fun IntRange.randomOrNull(random: Random): Int? = if (isEmpty()) null else random.nextInt(this)

public fun IntRange.randomOrNull(): Int? = randomOrNull(Random.Default)

public fun LongRange.random(random: Random): Long {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextLong(this)
}

public fun LongRange.random(): Long = random(Random.Default)

public fun LongRange.randomOrNull(random: Random): Long? = if (isEmpty()) null else random.nextLong(this)

public fun LongRange.randomOrNull(): Long? = randomOrNull(Random.Default)

public fun CharRange.random(random: Random): Char {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextInt(first.code, last.code + 1).toChar()
}

public fun CharRange.random(): Char = random(Random.Default)

public fun CharRange.randomOrNull(random: Random): Char? = if (isEmpty()) null else random.nextInt(first.code, last.code + 1).toChar()

public fun CharRange.randomOrNull(): Char? = randomOrNull(Random.Default)

public fun UIntRange.random(random: Random): UInt {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextUInt(this)
}

public fun UIntRange.random(): UInt = random(Random.Default)

public fun UIntRange.randomOrNull(random: Random): UInt? = if (isEmpty()) null else random.nextUInt(this)

public fun UIntRange.randomOrNull(): UInt? = randomOrNull(Random.Default)

public fun ULongRange.random(random: Random): ULong {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextULong(this)
}

public fun ULongRange.random(): ULong = random(Random.Default)

public fun ULongRange.randomOrNull(random: Random): ULong? = if (isEmpty()) null else random.nextULong(this)

public fun ULongRange.randomOrNull(): ULong? = randomOrNull(Random.Default)
