package kotlin.ranges

import kotlin.random.Random

public fun IntRange.random(random: Random): Int {
    if (isEmpty()) throw NoSuchElementException("Cannot get random in empty range: " + this)
    return random.nextInt(start, endInclusive + 1)
}

public fun IntRange.random(): Int = random(Random.Default)
