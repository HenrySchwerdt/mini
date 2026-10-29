/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reimplemented after kotlin-stdlib text/Strings.kt and StringsJVM.kt: same behaviour, written on top of
 * String.get/length (no regex, ASCII/Latin-1 case mapping only).
 */
package kotlin.text

import mini.internal.stringSubstring

public val String.indices: IntRange
    get() = 0..length - 1

public val String.lastIndex: Int
    get() = length - 1

public fun String.isEmpty(): Boolean = length == 0

public fun String.isNotEmpty(): Boolean = length > 0

public fun String.isBlank(): Boolean {
    var i = 0
    while (i < length) {
        if (!this[i].isWhitespace()) return false
        i++
    }
    return true
}

public fun String.isNotBlank(): Boolean = !isBlank()

public fun String?.isNullOrEmpty(): Boolean = this == null || this.length == 0

public fun String?.isNullOrBlank(): Boolean = this == null || this.isBlank()

public fun String.substring(startIndex: Int): String = substring(startIndex, length)

public fun String.substring(startIndex: Int, endIndex: Int): String {
    if (startIndex < 0 || endIndex > length || startIndex > endIndex) {
        mini.internal.throwIndexOutOfBounds("begin $startIndex, end $endIndex, length $length")
    }
    return stringSubstring(this, startIndex, endIndex)
}

public fun String.substringBefore(delimiter: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(0, index)
}

public fun String.substringAfter(delimiter: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(index + delimiter.length, length)
}

public fun String.substringBeforeLast(delimiter: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(0, index)
}

public fun String.substringAfterLast(delimiter: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else substring(index + delimiter.length, length)
}

public fun String.take(n: Int): String {
    if (n < 0) mini.internal.throwIllegalArgument("Requested character count $n is less than zero.")
    return substring(0, if (n < length) n else length)
}

public fun String.takeLast(n: Int): String {
    if (n < 0) mini.internal.throwIllegalArgument("Requested character count $n is less than zero.")
    val l = length
    return substring(l - (if (n < l) n else l))
}

public fun String.drop(n: Int): String {
    if (n < 0) mini.internal.throwIllegalArgument("Requested character count $n is less than zero.")
    return substring(if (n < length) n else length)
}

public fun String.dropLast(n: Int): String {
    if (n < 0) mini.internal.throwIllegalArgument("Requested character count $n is less than zero.")
    return take(if (length - n > 0) length - n else 0)
}

public fun String.startsWith(prefix: String): Boolean = regionMatches(0, prefix, 0, prefix.length)

public fun String.startsWith(prefix: String, startIndex: Int): Boolean =
    startIndex >= 0 && regionMatches(startIndex, prefix, 0, prefix.length)

public fun String.startsWith(char: Char): Boolean = length > 0 && this[0] == char

public fun String.endsWith(suffix: String): Boolean =
    length >= suffix.length && regionMatches(length - suffix.length, suffix, 0, suffix.length)

public fun String.endsWith(char: Char): Boolean = length > 0 && this[length - 1] == char

public fun String.regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int): Boolean {
    if (otherOffset < 0 || thisOffset < 0 || thisOffset > this.length - length || otherOffset > other.length - length) return false
    var i = 0
    while (i < length) {
        if (this[thisOffset + i] != other[otherOffset + i]) return false
        i++
    }
    return true
}

public fun String.removePrefix(prefix: String): String = if (startsWith(prefix)) substring(prefix.length) else this

public fun String.removeSuffix(suffix: String): String = if (endsWith(suffix)) substring(0, length - suffix.length) else this

public fun String.indexOf(char: Char, startIndex: Int = 0): Int {
    var i = if (startIndex < 0) 0 else startIndex
    while (i < length) {
        if (this[i] == char) return i
        i++
    }
    return -1
}

public fun String.indexOf(string: String, startIndex: Int = 0): Int {
    var i = if (startIndex < 0) 0 else startIndex
    val last = length - string.length
    while (i <= last) {
        if (regionMatches(i, string, 0, string.length)) return i
        i++
    }
    return -1
}

public fun String.lastIndexOf(char: Char): Int {
    var i = length - 1
    while (i >= 0) {
        if (this[i] == char) return i
        i--
    }
    return -1
}

public fun String.lastIndexOf(string: String): Int {
    var i = length - string.length
    while (i >= 0) {
        if (regionMatches(i, string, 0, string.length)) return i
        i--
    }
    return -1
}

public operator fun String.contains(other: String): Boolean = indexOf(other) >= 0

public operator fun String.contains(char: Char): Boolean = indexOf(char) >= 0

public fun String.trim(): String {
    var start = 0
    var end = length - 1
    while (start <= end && this[start].isWhitespace()) start++
    while (end >= start && this[end].isWhitespace()) end--
    return substring(start, end + 1)
}

public fun String.trimStart(): String {
    var start = 0
    while (start < length && this[start].isWhitespace()) start++
    return substring(start)
}

public fun String.trimEnd(): String {
    var end = length
    while (end > 0 && this[end - 1].isWhitespace()) end--
    return substring(0, end)
}

public inline fun String.trim(predicate: (Char) -> Boolean): String {
    var start = 0
    var end = length - 1
    while (start <= end && predicate(this[start])) start++
    while (end >= start && predicate(this[end])) end--
    return substring(start, end + 1)
}

public fun String.padStart(length: Int, padChar: Char = ' '): String {
    if (length < 0) mini.internal.throwIllegalArgument("Desired length $length is less than zero.")
    if (length <= this.length) return this
    val sb = StringBuilder(length)
    var i = 1
    while (i <= length - this.length) {
        sb.append(padChar)
        i++
    }
    sb.append(this)
    return sb.toString()
}

public fun String.padEnd(length: Int, padChar: Char = ' '): String {
    if (length < 0) mini.internal.throwIllegalArgument("Desired length $length is less than zero.")
    if (length <= this.length) return this
    val sb = StringBuilder(length)
    sb.append(this)
    var i = 1
    while (i <= length - this.length) {
        sb.append(padChar)
        i++
    }
    return sb.toString()
}

public fun String.repeat(n: Int): String {
    if (n < 0) mini.internal.throwIllegalArgument("Count 'n' must be non-negative, but was $n.")
    return when (n) {
        0 -> ""
        1 -> this
        else -> {
            val sb = StringBuilder(n * length)
            var i = 1
            while (i <= n) {
                sb.append(this)
                i++
            }
            sb.toString()
        }
    }
}

public fun String.reversed(): String = StringBuilder(this).reverse().toString()

public fun String.lowercase(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        sb.append(this[i].lowercaseChar())
        i++
    }
    return sb.toString()
}

public fun String.uppercase(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        sb.append(this[i].uppercaseChar())
        i++
    }
    return sb.toString()
}

public fun String.replace(oldChar: Char, newChar: Char): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        sb.append(if (c == oldChar) newChar else c)
        i++
    }
    return sb.toString()
}

public fun String.replace(oldValue: String, newValue: String): String {
    if (oldValue.length == 0) {
        // Kotlin inserts newValue between all characters and at both ends
        val sb = StringBuilder()
        sb.append(newValue)
        var i = 0
        while (i < length) {
            sb.append(this[i])
            sb.append(newValue)
            i++
        }
        return sb.toString()
    }
    var occurrenceIndex = indexOf(oldValue)
    if (occurrenceIndex < 0) return this
    val sb = StringBuilder(length)
    var i = 0
    while (occurrenceIndex >= 0) {
        sb.append(substring(i, occurrenceIndex))
        sb.append(newValue)
        i = occurrenceIndex + oldValue.length
        occurrenceIndex = indexOf(oldValue, i)
    }
    sb.append(substring(i))
    return sb.toString()
}

public fun String.split(delimiter: String): List<String> {
    val result = ArrayList<String>()
    if (delimiter.length == 0) {
        var k = 0
        while (k < length) {
            result.add(this[k].toString())
            k++
        }
        return result
    }
    var start = 0
    var index = indexOf(delimiter)
    while (index >= 0) {
        result.add(substring(start, index))
        start = index + delimiter.length
        index = indexOf(delimiter, start)
    }
    result.add(substring(start))
    return result
}

public fun String.split(delimiter: Char): List<String> {
    val result = ArrayList<String>()
    var start = 0
    var index = indexOf(delimiter)
    while (index >= 0) {
        result.add(substring(start, index))
        start = index + 1
        index = indexOf(delimiter, start)
    }
    result.add(substring(start))
    return result
}

public fun String.lines(): List<String> {
    val result = ArrayList<String>()
    var start = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\n') {
            result.add(substring(start, i))
            start = i + 1
        } else if (c == '\r') {
            result.add(substring(start, i))
            if (i + 1 < length && this[i + 1] == '\n') i++
            start = i + 1
        }
        i++
    }
    result.add(substring(start))
    return result
}

public fun String?.equals(other: String?, ignoreCase: Boolean): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    return if (ignoreCase) this.lowercase() == other.lowercase() else this == other
}

public inline fun String.count(predicate: (Char) -> Boolean): Int {
    var n = 0
    var i = 0
    while (i < length) {
        if (predicate(this[i])) n++
        i++
    }
    return n
}

public inline fun String.forEach(action: (Char) -> Unit) {
    var i = 0
    while (i < length) {
        action(this[i])
        i++
    }
}

public inline fun String.filter(predicate: (Char) -> Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < length) {
        val c = this[i]
        if (predicate(c)) sb.append(c)
        i++
    }
    return sb.toString()
}

public inline fun String.any(predicate: (Char) -> Boolean): Boolean {
    var i = 0
    while (i < length) {
        if (predicate(this[i])) return true
        i++
    }
    return false
}

public inline fun String.all(predicate: (Char) -> Boolean): Boolean {
    var i = 0
    while (i < length) {
        if (!predicate(this[i])) return false
        i++
    }
    return true
}

public fun String.first(): Char {
    if (length == 0) mini.internal.throwNoSuchElement("Char sequence is empty.")
    return this[0]
}

public fun String.last(): Char {
    if (length == 0) mini.internal.throwNoSuchElement("Char sequence is empty.")
    return this[length - 1]
}

public fun String.firstOrNull(): Char? = if (length == 0) null else this[0]

public fun String.lastOrNull(): Char? = if (length == 0) null else this[length - 1]

public fun String.toList(): List<Char> {
    val r = ArrayList<Char>(length)
    var i = 0
    while (i < length) {
        r.add(this[i])
        i++
    }
    return r
}

public fun String.trimIndent(): String = replaceIndent("")

public fun String.replaceIndent(newIndent: String = ""): String {
    val lines = lines()
    var minIndent = Int.MAX_VALUE
    for (line in lines) {
        if (line.isBlank()) continue
        var n = 0
        while (n < line.length && line[n].isWhitespace()) n++
        if (n < minIndent) minIndent = n
    }
    if (minIndent == Int.MAX_VALUE) minIndent = 0
    // first and last line are dropped when blank (as in trimIndent)
    var from = 0
    var to = lines.size
    if (to > 0 && lines[0].isBlank()) from = 1
    if (to > from && lines[to - 1].isBlank()) to--
    val sb = StringBuilder()
    var i = from
    while (i < to) {
        val line = lines[i]
        if (i > from) sb.append('\n')
        if (!line.isBlank()) sb.append(newIndent).append(line.substring(if (minIndent < line.length) minIndent else line.length))
        i++
    }
    return sb.toString()
}
