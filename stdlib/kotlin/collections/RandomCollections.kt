package kotlin.collections

import kotlin.random.Random


public fun <T> MutableList<T>.shuffle(random: Random) {
    var i = size - 1
    while (i >= 1) {
        val j = random.nextInt(i + 1)
        val t = this[i]
        this[i] = this[j]
        this[j] = t
        i--
    }
}

public fun <T> MutableList<T>.shuffle() { shuffle(Random.Default) }

public fun <T> Iterable<T>.shuffled(random: Random): List<T> {
    val l = toMutableList()
    l.shuffle(random)
    return l
}

public fun <T> Iterable<T>.shuffled(): List<T> = shuffled(Random.Default)

public fun <T> Collection<T>.random(random: Random): T {
    if (isEmpty()) throw NoSuchElementException("Collection is empty.")
    return elementAt(random.nextInt(size))
}

public fun <T> Collection<T>.random(): T = random(Random.Default)

public fun <T> Collection<T>.randomOrNull(random: Random): T? {
    if (isEmpty()) return null
    return elementAt(random.nextInt(size))
}

public fun <T> Collection<T>.randomOrNull(): T? = randomOrNull(Random.Default)

