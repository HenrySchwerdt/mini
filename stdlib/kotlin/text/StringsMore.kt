/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib text/Strings.kt and generated/_Strings.kt (v1-A additions).
 * Like Strings.kt these are declared on String (CharSequence is erased in Mini).
 */
package kotlin.text

// ---- iteration -----------------------------------------------------------------------------------------

public operator fun String.iterator(): CharIterator = StringCharIterator(this)

private class StringCharIterator(private val s: String) : CharIterator() {
    private var index = 0

    override fun nextChar(): Char = if (index < s.length) s[index++] else throw IndexOutOfBoundsException("$index")

    override fun hasNext(): Boolean = index < s.length
}

public fun String.withIndex(): Iterable<IndexedValue<Char>> {
    val list = ArrayList<IndexedValue<Char>>(length)
    var index = 0
    for (item in this) list.add(IndexedValue(index++, item))
    return list
}

public inline fun String.forEachIndexed(action: (index: Int, Char) -> Unit) {
    var index = 0
    for (item in this) action(index++, item)
}

public inline fun <R> String.map(transform: (Char) -> R): List<R> {
    val destination = ArrayList<R>(length)
    for (item in this) destination.add(transform(item))
    return destination
}

public inline fun <R> String.mapIndexed(transform: (index: Int, Char) -> R): List<R> {
    val destination = ArrayList<R>(length)
    var index = 0
    for (item in this) destination.add(transform(index++, item))
    return destination
}

public inline fun String.none(predicate: (Char) -> Boolean): Boolean {
    for (element in this) if (predicate(element)) return false
    return true
}

public fun String.none(): Boolean = isEmpty()

public fun String.any(): Boolean = isNotEmpty()

public inline fun String.indexOfFirst(predicate: (Char) -> Boolean): Int {
    for (index in indices) {
        if (predicate(this[index])) return index
    }
    return -1
}

public inline fun String.indexOfLast(predicate: (Char) -> Boolean): Int {
    for (index in indices.reversed()) {
        if (predicate(this[index])) return index
    }
    return -1
}

public inline fun String.first(predicate: (Char) -> Boolean): Char {
    for (element in this) if (predicate(element)) return element
    throw NoSuchElementException("Char sequence contains no character matching the predicate.")
}

public inline fun String.firstOrNull(predicate: (Char) -> Boolean): Char? {
    for (element in this) if (predicate(element)) return element
    return null
}

public inline fun String.sumOf(selector: (Char) -> Int): Int {
    var sum = 0
    for (element in this) sum += selector(element)
    return sum
}

public inline fun String.takeWhile(predicate: (Char) -> Boolean): String {
    for (index in 0 until length) {
        if (!predicate(this[index])) return substring(0, index)
    }
    return this
}

public inline fun String.dropWhile(predicate: (Char) -> Boolean): String {
    for (index in this.indices) {
        if (!predicate(this[index])) return substring(index)
    }
    return ""
}

public inline fun String.takeLastWhile(predicate: (Char) -> Boolean): String {
    for (index in lastIndex downTo 0) {
        if (!predicate(this[index])) return substring(index + 1)
    }
    return this
}

public fun String.toCharArray(): CharArray {
    val result = CharArray(length)
    for (i in 0 until length) result[i] = this[i]
    return result
}

public fun CharArray.concatToString(): String {
    val sb = StringBuilder()
    for (c in this) sb.append(c)
    return sb.toString()
}

// ---- empty / blank --------------------------------------------------------------------------------------

public fun String?.orEmpty(): String = this ?: ""

public inline fun String.ifEmpty(defaultValue: () -> String): String = if (isEmpty()) defaultValue() else this

public inline fun String.ifBlank(defaultValue: () -> String): String = if (isBlank()) defaultValue() else this

// ---- substring variants with a Char delimiter ---------------------------------------------------------------

public fun String.substringBefore(delimiter: Char, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(0, index)
}

public fun String.substringAfter(delimiter: Char, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(index + 1, length)
}

public fun String.substringBeforeLast(delimiter: Char, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(0, index)
}

public fun String.substringAfterLast(delimiter: Char, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(index + 1, length)
}

public fun String.removeSurrounding(prefix: String, suffix: String): String {
    if ((length >= prefix.length + suffix.length) && startsWith(prefix) && endsWith(suffix)) {
        return substring(prefix.length, length - suffix.length)
    }
    return this
}

public fun String.removeSurrounding(delimiter: String): String = removeSurrounding(delimiter, delimiter)

public fun String.removeRange(startIndex: Int, endIndex: Int): String {
    if (endIndex < startIndex) throw IndexOutOfBoundsException("End index ($endIndex) is less than start index ($startIndex).")
    if (endIndex == startIndex) return this
    val sb = StringBuilder(length - (endIndex - startIndex))
    sb.append(substring(0, startIndex))
    sb.append(substring(endIndex, length))
    return sb.toString()
}

public fun String.lastIndexOf(char: Char, startIndex: Int): Int {
    var i = if (startIndex >= length) length - 1 else startIndex
    while (i >= 0) {
        if (this[i] == char) return i
        i--
    }
    return -1
}

public fun String.lastIndexOf(string: String, startIndex: Int): Int {
    var i = if (startIndex > length - string.length) length - string.length else startIndex
    while (i >= 0) {
        if (regionMatches(i, string, 0, string.length)) return i
        i--
    }
    return -1
}

public fun String.commonPrefixWith(other: String): String {
    val shortestLength = minOf(length, other.length)
    var i = 0
    while (i < shortestLength && this[i] == other[i]) i++
    return substring(0, i)
}

public fun String.chunked(size: Int): List<String> {
    if (size <= 0) throw IllegalArgumentException("size $size must be greater than zero.")
    val result = ArrayList<String>()
    var i = 0
    while (i < length) {
        result.add(substring(i, if (i + size > length) length else i + size))
        i += size
    }
    return result
}
