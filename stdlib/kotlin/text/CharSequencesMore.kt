/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib text/Strings.kt and generated/_Strings.kt: the rest of kotlin.text for CharSequence receivers.
 * A CharSequence is a String or an object that implements the interface (StringBuilder, user classes). The String overloads of
 * Strings.kt / StringsMore.kt / StringsExtra.kt are more specific and keep the direct paths; the functions here go through
 * `toString()` (the identity for a String) unless they can work on `length` / `get` alone.
 */
package kotlin.text

public fun CharSequence.isEmpty(): Boolean = length == 0

public fun CharSequence.isNotEmpty(): Boolean = length > 0

public fun CharSequence.isBlank(): Boolean {
    var i = 0
    while (i < length) {
        if (!this[i].isWhitespace()) return false
        i++
    }
    return true
}

public fun CharSequence.isNotBlank(): Boolean = !isBlank()

public fun CharSequence?.isNullOrEmpty(): Boolean = this == null || this.length == 0

public fun CharSequence?.isNullOrBlank(): Boolean = this == null || this.isBlank()

public fun CharSequence.trim(): CharSequence {
    var start = 0
    var end = length - 1
    while (start <= end && this[start].isWhitespace()) start++
    while (end >= start && this[end].isWhitespace()) end--
    return subSequence(start, end + 1)
}

public fun CharSequence.trimStart(): CharSequence {
    var start = 0
    while (start < length && this[start].isWhitespace()) start++
    return subSequence(start, length)
}

public fun CharSequence.trimEnd(): CharSequence {
    var end = length - 1
    while (end >= 0 && this[end].isWhitespace()) end--
    return subSequence(0, end + 1)
}

public inline fun CharSequence.trim(predicate: (Char) -> Boolean): CharSequence {
    var start = 0
    var end = length - 1
    while (start <= end && predicate(this[start])) start++
    while (end >= start && predicate(this[end])) end--
    return subSequence(start, end + 1)
}

public inline fun CharSequence.trimStart(predicate: (Char) -> Boolean): CharSequence {
    var start = 0
    while (start < length && predicate(this[start])) start++
    return subSequence(start, length)
}

public inline fun CharSequence.trimEnd(predicate: (Char) -> Boolean): CharSequence {
    var end = length - 1
    while (end >= 0 && predicate(this[end])) end--
    return subSequence(0, end + 1)
}

public fun CharSequence.startsWith(prefix: CharSequence, ignoreCase: Boolean = false): Boolean =
    toString().startsWith(prefix.toString(), ignoreCase)

public fun CharSequence.startsWith(prefix: CharSequence, startIndex: Int, ignoreCase: Boolean = false): Boolean =
    regionMatches(startIndex, prefix, 0, prefix.length, ignoreCase)

public fun CharSequence.startsWith(char: Char, ignoreCase: Boolean = false): Boolean =
    length > 0 && this[0].equals(char, ignoreCase)

public fun CharSequence.endsWith(suffix: CharSequence, ignoreCase: Boolean = false): Boolean =
    toString().endsWith(suffix.toString(), ignoreCase)

public fun CharSequence.endsWith(char: Char, ignoreCase: Boolean = false): Boolean =
    length > 0 && this[length - 1].equals(char, ignoreCase)

public operator fun CharSequence.contains(other: CharSequence, ignoreCase: Boolean = false): Boolean =
    toString().contains(other.toString(), ignoreCase)

public operator fun CharSequence.contains(char: Char, ignoreCase: Boolean = false): Boolean =
    indexOf(char, 0, ignoreCase) >= 0

public operator fun CharSequence.contains(regex: Regex): Boolean = regex.containsMatchIn(toString())

public fun CharSequence.indexOf(char: Char, startIndex: Int = 0, ignoreCase: Boolean = false): Int {
    var i = if (startIndex < 0) 0 else startIndex
    while (i < length) {
        if (this[i].equals(char, ignoreCase)) return i
        i++
    }
    return -1
}

public fun CharSequence.indexOf(string: String, startIndex: Int = 0, ignoreCase: Boolean = false): Int =
    toString().indexOf(string, startIndex, ignoreCase)

public fun CharSequence.lastIndexOf(char: Char, startIndex: Int = lastIndex, ignoreCase: Boolean = false): Int {
    var i = if (startIndex >= length) length - 1 else startIndex
    while (i >= 0) {
        if (this[i].equals(char, ignoreCase)) return i
        i--
    }
    return -1
}

public fun CharSequence.lastIndexOf(string: String, startIndex: Int = lastIndex, ignoreCase: Boolean = false): Int =
    toString().lastIndexOf(string, startIndex, ignoreCase)

public fun CharSequence.indexOfAny(chars: CharArray, startIndex: Int = 0, ignoreCase: Boolean = false): Int =
    toString().indexOfAny(chars, startIndex, ignoreCase)

public fun CharSequence.indexOfAny(strings: Collection<String>, startIndex: Int = 0, ignoreCase: Boolean = false): Int =
    toString().indexOfAny(strings, startIndex, ignoreCase)

public fun CharSequence.findAnyOf(strings: Collection<String>, startIndex: Int = 0, ignoreCase: Boolean = false): Pair<Int, String>? =
    toString().findAnyOf(strings, startIndex, ignoreCase)

public fun CharSequence.regionMatches(thisOffset: Int, other: CharSequence, otherOffset: Int, length: Int, ignoreCase: Boolean = false): Boolean {
    if (otherOffset < 0 || thisOffset < 0 || thisOffset > this.length - length || otherOffset > other.length - length) return false
    for (index in 0 until length) {
        if (!this[thisOffset + index].equals(other[otherOffset + index], ignoreCase)) return false
    }
    return true
}

public fun CharSequence?.contentEquals(other: CharSequence?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    if (length != other.length) return false
    for (i in 0 until length) if (this[i] != other[i]) return false
    return true
}

public fun CharSequence?.contentEquals(other: CharSequence?, ignoreCase: Boolean): Boolean {
    if (!ignoreCase) return contentEquals(other)
    if (this === other) return true
    if (this == null || other == null) return false
    if (length != other.length) return false
    for (i in 0 until length) if (!this[i].equals(other[i], true)) return false
    return true
}

public fun CharSequence.commonPrefixWith(other: CharSequence, ignoreCase: Boolean = false): CharSequence {
    val shortestLength = minOf(length, other.length)
    var i = 0
    while (i < shortestLength && this[i].equals(other[i], ignoreCase)) i++
    return subSequence(0, i)
}

public fun CharSequence.commonSuffixWith(other: CharSequence, ignoreCase: Boolean = false): CharSequence {
    val thisLength = length
    val otherLength = other.length
    val shortestLength = minOf(thisLength, otherLength)
    var i = 0
    while (i < shortestLength && this[thisLength - i - 1].equals(other[otherLength - i - 1], ignoreCase)) i++
    return subSequence(thisLength - i, thisLength)
}

public fun CharSequence.hasSurrogatePairAt(index: Int): Boolean =
    index >= 0 && index <= length - 2 && this[index].isHighSurrogate() && this[index + 1].isLowSurrogate()

public fun CharSequence.lines(): List<String> = toString().lines()

public fun CharSequence.lineSequence(): Sequence<String> = toString().lineSequence()

public fun CharSequence.split(vararg delimiters: String, ignoreCase: Boolean = false, limit: Int = 0): List<String> =
    toString().split(*delimiters, ignoreCase = ignoreCase, limit = limit)

public fun CharSequence.split(vararg delimiters: Char, ignoreCase: Boolean = false, limit: Int = 0): List<String> =
    toString().split(*delimiters, ignoreCase = ignoreCase, limit = limit)

public fun CharSequence.split(regex: Regex, limit: Int = 0): List<String> = regex.split(toString(), limit)

public fun CharSequence.splitToSequence(vararg delimiters: String, ignoreCase: Boolean = false, limit: Int = 0): Sequence<String> =
    toString().splitToSequence(*delimiters, ignoreCase = ignoreCase, limit = limit)

public fun CharSequence.splitToSequence(vararg delimiters: Char, ignoreCase: Boolean = false, limit: Int = 0): Sequence<String> =
    toString().splitToSequence(*delimiters, ignoreCase = ignoreCase, limit = limit)

public fun CharSequence.substring(startIndex: Int, endIndex: Int = length): String = subSequence(startIndex, endIndex).toString()

public fun CharSequence.substring(range: IntRange): String = subSequence(range.start, range.endInclusive + 1).toString()

public fun CharSequence.subSequence(range: IntRange): CharSequence = subSequence(range.start, range.endInclusive + 1)

public fun CharSequence.slice(indices: IntRange): CharSequence =
    if (indices.isEmpty()) "" else subSequence(indices)

public fun CharSequence.slice(indices: Iterable<Int>): CharSequence {
    val sb = StringBuilder()
    for (i in indices) sb.append(this[i])
    return sb
}

public fun CharSequence.take(n: Int): CharSequence {
    require(n >= 0) { "Requested character count $n is less than zero." }
    return subSequence(0, if (n < length) n else length)
}

public fun CharSequence.takeLast(n: Int): CharSequence {
    require(n >= 0) { "Requested character count $n is less than zero." }
    val l = length
    return subSequence(l - (if (n < l) n else l), l)
}

public fun CharSequence.drop(n: Int): CharSequence {
    require(n >= 0) { "Requested character count $n is less than zero." }
    return subSequence(if (n < length) n else length, length)
}

public fun CharSequence.dropLast(n: Int): CharSequence {
    require(n >= 0) { "Requested character count $n is less than zero." }
    return take(if (length - n > 0) length - n else 0)
}

public inline fun CharSequence.takeWhile(predicate: (Char) -> Boolean): CharSequence {
    for (index in 0 until length) if (!predicate(this[index])) return subSequence(0, index)
    return subSequence(0, length)
}

public inline fun CharSequence.dropWhile(predicate: (Char) -> Boolean): CharSequence {
    for (index in 0 until length) if (!predicate(this[index])) return subSequence(index, length)
    return subSequence(length, length)
}

public inline fun CharSequence.takeLastWhile(predicate: (Char) -> Boolean): CharSequence {
    for (index in lastIndex downTo 0) if (!predicate(this[index])) return subSequence(index + 1, length)
    return subSequence(0, length)
}

public inline fun CharSequence.dropLastWhile(predicate: (Char) -> Boolean): CharSequence {
    for (index in lastIndex downTo 0) if (!predicate(this[index])) return subSequence(0, index + 1)
    return subSequence(0, 0)
}

public fun CharSequence.removePrefix(prefix: CharSequence): CharSequence =
    if (startsWith(prefix)) subSequence(prefix.length, length) else subSequence(0, length)

public fun CharSequence.removeSuffix(suffix: CharSequence): CharSequence =
    if (endsWith(suffix)) subSequence(0, length - suffix.length) else subSequence(0, length)

public fun CharSequence.removeSurrounding(prefix: CharSequence, suffix: CharSequence): CharSequence =
    if (length >= prefix.length + suffix.length && startsWith(prefix) && endsWith(suffix)) subSequence(prefix.length, length - suffix.length)
    else subSequence(0, length)

public fun CharSequence.removeSurrounding(delimiter: CharSequence): CharSequence = removeSurrounding(delimiter, delimiter)

public fun CharSequence.removeRange(startIndex: Int, endIndex: Int): CharSequence {
    if (endIndex < startIndex) throw IndexOutOfBoundsException("End index ($endIndex) is less than start index ($startIndex).")
    if (endIndex == startIndex) return subSequence(0, length)
    val sb = StringBuilder(length - (endIndex - startIndex))
    sb.append(subSequence(0, startIndex))
    sb.append(subSequence(endIndex, length))
    return sb
}

public fun CharSequence.removeRange(range: IntRange): CharSequence = removeRange(range.start, range.endInclusive + 1)

public fun CharSequence.replaceRange(startIndex: Int, endIndex: Int, replacement: CharSequence): CharSequence {
    if (endIndex < startIndex) throw IndexOutOfBoundsException("End index ($endIndex) is less than start index ($startIndex).")
    val sb = StringBuilder()
    sb.append(subSequence(0, startIndex))
    sb.append(replacement)
    sb.append(subSequence(endIndex, length))
    return sb
}

public fun CharSequence.replaceRange(range: IntRange, replacement: CharSequence): CharSequence =
    replaceRange(range.start, range.endInclusive + 1, replacement)

public fun CharSequence.replace(regex: Regex, replacement: String): String = regex.replace(toString(), replacement)

public fun CharSequence.replace(regex: Regex, transform: (MatchResult) -> CharSequence): String = regex.replace(toString(), transform)

public fun CharSequence.replaceFirst(regex: Regex, replacement: String): String = regex.replaceFirst(toString(), replacement)

public fun CharSequence.matches(regex: Regex): Boolean = regex.matches(toString())

public fun CharSequence.padStart(length: Int, padChar: Char = ' '): CharSequence {
    if (length < 0) throw IllegalArgumentException("Desired length $length is less than zero.")
    if (length <= this.length) return subSequence(0, this.length)
    val sb = StringBuilder(length)
    for (i in 1..(length - this.length)) sb.append(padChar)
    sb.append(this)
    return sb
}

public fun CharSequence.padEnd(length: Int, padChar: Char = ' '): CharSequence {
    if (length < 0) throw IllegalArgumentException("Desired length $length is less than zero.")
    if (length <= this.length) return subSequence(0, this.length)
    val sb = StringBuilder(length)
    sb.append(this)
    for (i in 1..(length - this.length)) sb.append(padChar)
    return sb
}

public fun CharSequence.repeat(n: Int): String {
    require(n >= 0) { "Count 'n' must be non-negative, but was $n." }
    return toString().repeat(n)
}

public fun CharSequence.reversed(): CharSequence = StringBuilder(toString()).reverse()

public fun CharSequence.chunked(size: Int): List<String> = windowed(size, size, true)

public fun CharSequence.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false): List<String> =
    windowed(size, step, partialWindows) { it.toString() }

public fun <R> CharSequence.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false, transform: (CharSequence) -> R): List<R> {
    require(size > 0 && step > 0) { "size $size and step $step must be greater than zero." }
    val thisSize = length
    val result = ArrayList<R>((thisSize + step - 1) / step)
    var index = 0
    while (index in 0 until thisSize) {
        val end = index + size
        val coercedEnd = if (end < 0 || end > thisSize) { if (partialWindows) thisSize else break } else end
        result.add(transform(subSequence(index, coercedEnd)))
        index += step
    }
    return result
}

public fun <R> CharSequence.chunked(size: Int, transform: (CharSequence) -> R): List<R> = windowed(size, size, true, transform)

public infix fun CharSequence.zip(other: CharSequence): List<Pair<Char, Char>> {
    val length = minOf(this.length, other.length)
    val list = ArrayList<Pair<Char, Char>>(length)
    for (i in 0 until length) list.add(Pair(this[i], other[i]))
    return list
}

public inline fun <V> CharSequence.zip(other: CharSequence, transform: (a: Char, b: Char) -> V): List<V> {
    val length = minOf(this.length, other.length)
    val list = ArrayList<V>(length)
    for (i in 0 until length) list.add(transform(this[i], other[i]))
    return list
}

public fun CharSequence.zipWithNext(): List<Pair<Char, Char>> {
    val size = length - 1
    if (size < 1) return emptyList()
    val result = ArrayList<Pair<Char, Char>>(size)
    for (index in 0 until size) result.add(Pair(this[index], this[index + 1]))
    return result
}

public inline fun <R> CharSequence.zipWithNext(transform: (a: Char, b: Char) -> R): List<R> {
    val size = length - 1
    if (size < 1) return emptyList()
    val result = ArrayList<R>(size)
    for (index in 0 until size) result.add(transform(this[index], this[index + 1]))
    return result
}

public fun CharSequence.asSequence(): Sequence<Char> = toList().asSequence()

public fun CharSequence.asIterable(): Iterable<Char> = toList()

public fun CharSequence.toList(): List<Char> {
    val result = ArrayList<Char>(length)
    for (i in 0 until length) result.add(this[i])
    return result
}

public fun CharSequence.toMutableList(): MutableList<Char> = toList().toMutableList()

public fun CharSequence.toSet(): Set<Char> = toList().toSet()

public fun CharSequence.toHashSet(): HashSet<Char> = HashSet<Char>(toList())

public fun CharSequence.count(): Int = length

public inline fun CharSequence.count(predicate: (Char) -> Boolean): Int {
    var count = 0
    for (i in 0 until length) if (predicate(this[i])) count++
    return count
}

public fun CharSequence.any(): Boolean = length > 0

public inline fun CharSequence.any(predicate: (Char) -> Boolean): Boolean {
    for (i in 0 until length) if (predicate(this[i])) return true
    return false
}

public fun CharSequence.none(): Boolean = length == 0

public inline fun CharSequence.none(predicate: (Char) -> Boolean): Boolean {
    for (i in 0 until length) if (predicate(this[i])) return false
    return true
}

public inline fun CharSequence.all(predicate: (Char) -> Boolean): Boolean {
    for (i in 0 until length) if (!predicate(this[i])) return false
    return true
}

public fun CharSequence.first(): Char {
    if (isEmpty()) throw NoSuchElementException("Char sequence is empty.")
    return this[0]
}

public fun CharSequence.last(): Char {
    if (isEmpty()) throw NoSuchElementException("Char sequence is empty.")
    return this[lastIndex]
}

public fun CharSequence.firstOrNull(): Char? = if (isEmpty()) null else this[0]

public fun CharSequence.lastOrNull(): Char? = if (isEmpty()) null else this[length - 1]

public inline fun CharSequence.first(predicate: (Char) -> Boolean): Char {
    for (i in 0 until length) { val c = this[i]; if (predicate(c)) return c }
    throw NoSuchElementException("Char sequence contains no character matching the predicate.")
}

public inline fun CharSequence.firstOrNull(predicate: (Char) -> Boolean): Char? {
    for (i in 0 until length) { val c = this[i]; if (predicate(c)) return c }
    return null
}

public inline fun CharSequence.last(predicate: (Char) -> Boolean): Char {
    for (i in lastIndex downTo 0) { val c = this[i]; if (predicate(c)) return c }
    throw NoSuchElementException("Char sequence contains no character matching the predicate.")
}

public inline fun CharSequence.lastOrNull(predicate: (Char) -> Boolean): Char? {
    for (i in lastIndex downTo 0) { val c = this[i]; if (predicate(c)) return c }
    return null
}

public inline fun CharSequence.find(predicate: (Char) -> Boolean): Char? = firstOrNull(predicate)

public inline fun CharSequence.findLast(predicate: (Char) -> Boolean): Char? = lastOrNull(predicate)

public fun CharSequence.single(): Char = when (length) {
    0 -> throw NoSuchElementException("Char sequence is empty.")
    1 -> this[0]
    else -> throw IllegalArgumentException("Char sequence has more than one element.")
}

public fun CharSequence.singleOrNull(): Char? = if (length == 1) this[0] else null

public inline fun CharSequence.single(predicate: (Char) -> Boolean): Char {
    var single: Char? = null
    var found = false
    for (i in 0 until length) {
        val c = this[i]
        if (predicate(c)) {
            if (found) throw IllegalArgumentException("Char sequence contains more than one matching element.")
            single = c
            found = true
        }
    }
    if (!found) throw NoSuchElementException("Char sequence contains no character matching the predicate.")
    return single!!
}

public inline fun CharSequence.singleOrNull(predicate: (Char) -> Boolean): Char? {
    var single: Char? = null
    var found = false
    for (i in 0 until length) {
        val c = this[i]
        if (predicate(c)) {
            if (found) return null
            single = c
            found = true
        }
    }
    return if (!found) null else single
}

public fun CharSequence.getOrNull(index: Int): Char? = if (index >= 0 && index < length) this[index] else null

public inline fun CharSequence.getOrElse(index: Int, defaultValue: (Int) -> Char): Char =
    if (index >= 0 && index < length) this[index] else defaultValue(index)

public fun CharSequence.elementAt(index: Int): Char = this[index]

public fun CharSequence.elementAtOrNull(index: Int): Char? = getOrNull(index)

public inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
    for (index in 0 until length) if (predicate(this[index])) return index
    return -1
}

public inline fun CharSequence.indexOfLast(predicate: (Char) -> Boolean): Int {
    for (index in lastIndex downTo 0) if (predicate(this[index])) return index
    return -1
}

public inline fun CharSequence.filter(predicate: (Char) -> Boolean): CharSequence {
    val sb = StringBuilder()
    for (index in 0 until length) { val c = this[index]; if (predicate(c)) sb.append(c) }
    return sb
}

public inline fun CharSequence.filterNot(predicate: (Char) -> Boolean): CharSequence {
    val sb = StringBuilder()
    for (index in 0 until length) { val c = this[index]; if (!predicate(c)) sb.append(c) }
    return sb
}

public inline fun CharSequence.filterIndexed(predicate: (index: Int, Char) -> Boolean): CharSequence {
    val sb = StringBuilder()
    for (index in 0 until length) { val c = this[index]; if (predicate(index, c)) sb.append(c) }
    return sb
}

public inline fun <R> CharSequence.map(transform: (Char) -> R): List<R> {
    val destination = ArrayList<R>(length)
    for (i in 0 until length) destination.add(transform(this[i]))
    return destination
}

public inline fun <R> CharSequence.mapIndexed(transform: (index: Int, Char) -> R): List<R> {
    val destination = ArrayList<R>(length)
    for (i in 0 until length) destination.add(transform(i, this[i]))
    return destination
}

public inline fun <R : Any> CharSequence.mapNotNull(transform: (Char) -> R?): List<R> {
    val destination = ArrayList<R>()
    for (i in 0 until length) { val r = transform(this[i]); if (r != null) destination.add(r) }
    return destination
}

public inline fun <R> CharSequence.flatMap(transform: (Char) -> Iterable<R>): List<R> {
    val destination = ArrayList<R>()
    for (i in 0 until length) destination.addAll(transform(this[i]))
    return destination
}

public inline fun <R> CharSequence.fold(initial: R, operation: (acc: R, Char) -> R): R {
    var accumulator = initial
    for (i in 0 until length) accumulator = operation(accumulator, this[i])
    return accumulator
}

public inline fun <R> CharSequence.foldIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): R {
    var accumulator = initial
    for (i in 0 until length) accumulator = operation(i, accumulator, this[i])
    return accumulator
}

public inline fun CharSequence.reduce(operation: (acc: Char, Char) -> Char): Char {
    if (isEmpty()) throw UnsupportedOperationException("Empty char sequence can't be reduced.")
    var accumulator = this[0]
    for (index in 1 until length) accumulator = operation(accumulator, this[index])
    return accumulator
}

public inline fun <K, V> CharSequence.associate(transform: (Char) -> Pair<K, V>): Map<K, V> {
    val destination = LinkedHashMap<K, V>()
    for (i in 0 until length) { val p = transform(this[i]); destination.put(p.first, p.second) }
    return destination
}

public inline fun <K> CharSequence.associateBy(keySelector: (Char) -> K): Map<K, Char> {
    val destination = LinkedHashMap<K, Char>()
    for (i in 0 until length) { val c = this[i]; destination.put(keySelector(c), c) }
    return destination
}

public inline fun <V> CharSequence.associateWith(valueSelector: (Char) -> V): Map<Char, V> {
    val destination = LinkedHashMap<Char, V>()
    for (i in 0 until length) { val c = this[i]; destination.put(c, valueSelector(c)) }
    return destination
}

public inline fun <K> CharSequence.groupBy(keySelector: (Char) -> K): Map<K, List<Char>> = toList().groupBy(keySelector)

public inline fun CharSequence.partition(predicate: (Char) -> Boolean): Pair<CharSequence, CharSequence> {
    val first = StringBuilder()
    val second = StringBuilder()
    for (index in 0 until length) {
        val element = this[index]
        if (predicate(element)) first.append(element) else second.append(element)
    }
    return Pair(first, second)
}

public inline fun CharSequence.onEach(action: (Char) -> Unit): CharSequence {
    for (i in 0 until length) action(this[i])
    return this
}

public inline fun CharSequence.sumOf(selector: (Char) -> Int): Int {
    var sum = 0
    for (i in 0 until length) sum += selector(this[i])
    return sum
}

public fun CharSequence.maxOrNull(): Char? {
    if (isEmpty()) return null
    var max = this[0]
    for (i in 1 until length) { val e = this[i]; if (max < e) max = e }
    return max
}

public fun CharSequence.minOrNull(): Char? {
    if (isEmpty()) return null
    var min = this[0]
    for (i in 1 until length) { val e = this[i]; if (min > e) min = e }
    return min
}

public fun CharSequence.random(random: kotlin.random.Random): Char {
    if (isEmpty()) throw NoSuchElementException("Char sequence is empty.")
    return this[random.nextInt(length)]
}
