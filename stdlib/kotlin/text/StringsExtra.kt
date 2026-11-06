/*
 * More String functions of kotlin.text (String receivers; CharSequence is not a usable receiver type, see docs/stdlib.md).
 * Semantics and messages follow kotlin-stdlib.
 */
package kotlin.text

// ---- search with ignoreCase ----------------------------------------------------------------------

private fun charEq(a: Char, b: Char, ignoreCase: Boolean): Boolean =
    a == b || (ignoreCase && (a.uppercaseChar() == b.uppercaseChar() || a.lowercaseChar() == b.lowercaseChar()))

private fun String.regionEq(thisOffset: Int, other: String, otherOffset: Int, len: Int, ignoreCase: Boolean): Boolean {
    if (otherOffset < 0 || thisOffset < 0 || thisOffset > length - len || otherOffset > other.length - len) return false
    var i = 0
    while (i < len) {
        if (!charEq(this[thisOffset + i], other[otherOffset + i], ignoreCase)) return false
        i++
    }
    return true
}

public fun String.startsWith(prefix: String, ignoreCase: Boolean): Boolean = regionEq(0, prefix, 0, prefix.length, ignoreCase)

public fun String.startsWith(char: Char, ignoreCase: Boolean): Boolean = length > 0 && charEq(this[0], char, ignoreCase)

public fun String.endsWith(suffix: String, ignoreCase: Boolean): Boolean =
    length >= suffix.length && regionEq(length - suffix.length, suffix, 0, suffix.length, ignoreCase)

public fun String.endsWith(char: Char, ignoreCase: Boolean): Boolean = length > 0 && charEq(this[length - 1], char, ignoreCase)

public fun String.indexOf(string: String, startIndex: Int, ignoreCase: Boolean): Int {
    var i = if (startIndex < 0) 0 else startIndex
    val last = length - string.length
    while (i <= last) {
        if (regionEq(i, string, 0, string.length, ignoreCase)) return i
        i++
    }
    return -1
}

public fun String.indexOf(char: Char, startIndex: Int, ignoreCase: Boolean): Int {
    var i = if (startIndex < 0) 0 else startIndex
    while (i < length) {
        if (charEq(this[i], char, ignoreCase)) return i
        i++
    }
    return -1
}

public fun String.lastIndexOf(string: String, startIndex: Int, ignoreCase: Boolean): Int {
    var i = if (startIndex > length - string.length) length - string.length else startIndex
    while (i >= 0) {
        if (regionEq(i, string, 0, string.length, ignoreCase)) return i
        i--
    }
    return -1
}

public fun String.lastIndexOf(char: Char, startIndex: Int, ignoreCase: Boolean): Int {
    var i = if (startIndex > length - 1) length - 1 else startIndex
    while (i >= 0) {
        if (charEq(this[i], char, ignoreCase)) return i
        i--
    }
    return -1
}

public operator fun String.contains(other: String, ignoreCase: Boolean): Boolean = indexOf(other, 0, ignoreCase) >= 0

public operator fun String.contains(char: Char, ignoreCase: Boolean): Boolean = indexOf(char, 0, ignoreCase) >= 0

public fun String.indexOfAny(strings: Collection<String>, startIndex: Int = 0, ignoreCase: Boolean = false): Int {
    val r = findAnyOf(strings, startIndex, ignoreCase)
    return if (r == null) -1 else r.first
}

public fun String.indexOfAny(chars: CharArray, startIndex: Int = 0, ignoreCase: Boolean = false): Int {
    var i = if (startIndex < 0) 0 else startIndex
    while (i < length) {
        for (c in chars) if (charEq(this[i], c, ignoreCase)) return i
        i++
    }
    return -1
}

public fun String.findAnyOf(strings: Collection<String>, startIndex: Int = 0, ignoreCase: Boolean = false): Pair<Int, String>? {
    var i = if (startIndex < 0) 0 else startIndex
    while (i <= length) {
        for (s in strings) if (regionEq(i, s, 0, s.length, ignoreCase)) return Pair(i, s)
        i++
    }
    return null
}

public fun String.compareTo(other: String, ignoreCase: Boolean): Int {
    if (!ignoreCase) return compareTo(other)
    val n = if (length < other.length) length else other.length
    var i = 0
    while (i < n) {
        var a = this[i]
        var b = other[i]
        if (a != b) {
            a = a.uppercaseChar()
            b = b.uppercaseChar()
            if (a != b) {
                a = a.lowercaseChar()
                b = b.lowercaseChar()
                if (a != b) return a.code - b.code
            }
        }
        i++
    }
    return length - other.length
}

public fun String.regionMatches(thisOffset: Int, other: String, otherOffset: Int, length: Int, ignoreCase: Boolean): Boolean =
    regionEq(thisOffset, other, otherOffset, length, ignoreCase)

public fun String.commonSuffixWith(other: String): String {
    var s = 0
    val m = if (length < other.length) length else other.length
    while (s < m && this[length - s - 1] == other[other.length - s - 1]) s++
    return substring(length - s, length)
}

// ---- split / lines ----------------------------------------------------------------------------------

public fun String.split(vararg delimiters: String, ignoreCase: Boolean = false, limit: Int = 0): List<String> {
    require(limit >= 0) { "Limit must be non-negative, but was " + limit + "." }
    val delims = delimiters.asList()
    val result = ArrayList<String>()
    if (delimiters.size == 1 && delimiters[0].length > 0 && !ignoreCase) {
        val d = delimiters[0]
        var cur = 0
        var next = indexOf(d, 0)
        if (next == -1 || limit == 1) {
            result.add(this)
            return result
        }
        do {
            result.add(substring(cur, next))
            cur = next + d.length
            if (limit > 0 && result.size == limit - 1) break
            next = indexOf(d, cur)
        } while (next != -1)
        result.add(substring(cur, length))
        return result
    }
    var currentStart = 0
    var nextSearch = 0
    var counter = 0
    while (true) {
        if ((limit > 0 && ++counter >= limit) || currentStart > length) {
            result.add(substring(currentStart, length))
            return result
        }
        val m = findAnyOf(delims, nextSearch, ignoreCase)
        if (m == null) {
            result.add(substring(currentStart, length))
            return result
        }
        result.add(substring(currentStart, m.first))
        currentStart = m.first + m.second.length
        nextSearch = currentStart + (if (m.second.length == 0) 1 else 0)
    }
}

public fun String.split(vararg delimiters: Char, ignoreCase: Boolean = false, limit: Int = 0): List<String> {
    val ds = ArrayList<String>()
    for (c in delimiters) ds.add(c.toString())
    return split(*ds.toTypedArray(), ignoreCase = ignoreCase, limit = limit)
}

public fun String.splitToSequence(vararg delimiters: String, ignoreCase: Boolean = false, limit: Int = 0): Sequence<String> =
    split(*delimiters, ignoreCase = ignoreCase, limit = limit).asSequence()

public fun String.splitToSequence(vararg delimiters: Char, ignoreCase: Boolean = false, limit: Int = 0): Sequence<String> =
    split(*delimiters, ignoreCase = ignoreCase, limit = limit).asSequence()

public fun String.lineSequence(): Sequence<String> = lines().asSequence()

public fun String.asSequence(): Sequence<Char> = toList().asSequence()

public fun String.asIterable(): Iterable<Char> = toList()

// ---- replace ------------------------------------------------------------------------------------------

public fun String.replace(oldValue: String, newValue: String, ignoreCase: Boolean): String {
    if (!ignoreCase) return replace(oldValue, newValue)
    val sb = StringBuilder()
    var cur = 0
    if (oldValue.length == 0) {
        sb.append(newValue)
        var i = 0
        while (i < length) {
            sb.append(this[i]).append(newValue)
            i++
        }
        return sb.toString()
    }
    var idx = indexOf(oldValue, 0, true)
    while (idx >= 0) {
        sb.append(substring(cur, idx)).append(newValue)
        cur = idx + oldValue.length
        idx = indexOf(oldValue, cur, true)
    }
    sb.append(substring(cur))
    return sb.toString()
}

public fun String.replace(oldChar: Char, newChar: Char, ignoreCase: Boolean): String {
    if (!ignoreCase) return replace(oldChar, newChar)
    val sb = StringBuilder()
    var i = 0
    while (i < length) {
        val c = this[i]
        sb.append(if (charEq(c, oldChar, true)) newChar else c)
        i++
    }
    return sb.toString()
}

public fun String.replaceFirst(oldValue: String, newValue: String, ignoreCase: Boolean = false): String {
    val index = indexOf(oldValue, 0, ignoreCase)
    return if (index < 0) this else substring(0, index) + newValue + substring(index + oldValue.length)
}

public fun String.replaceFirst(oldChar: Char, newChar: Char, ignoreCase: Boolean = false): String {
    val index = indexOf(oldChar, 0, ignoreCase)
    return if (index < 0) this else substring(0, index) + newChar + substring(index + 1)
}

public fun String.replaceRange(startIndex: Int, endIndex: Int, replacement: String): String {
    if (endIndex < startIndex) throw IndexOutOfBoundsException("End index (" + endIndex + ") is less than start index (" + startIndex + ").")
    return substring(0, startIndex) + replacement + substring(endIndex, length)
}

public fun String.replaceRange(range: IntRange, replacement: String): String = replaceRange(range.start, range.endInclusive + 1, replacement)

public fun String.removeRange(range: IntRange): String = removeRange(range.start, range.endInclusive + 1)

public fun String.replaceBefore(delimiter: String, replacement: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(0, index, replacement)
}

public fun String.replaceAfter(delimiter: String, replacement: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(index + delimiter.length, length, replacement)
}

public fun String.replaceBeforeLast(delimiter: String, replacement: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(0, index, replacement)
}

public fun String.replaceAfterLast(delimiter: String, replacement: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(index + delimiter.length, length, replacement)
}

public fun String.substring(range: IntRange): String = substring(range.start, range.endInclusive + 1)

public fun String.slice(indices: IntRange): String {
    if (indices.isEmpty()) return ""
    return substring(indices.start, indices.endInclusive + 1)
}

public fun String.slice(indices: Iterable<Int>): String {
    val sb = StringBuilder()
    for (i in indices) sb.append(this[i])
    return sb.toString()
}

public fun String.subSequence(range: IntRange): String = substring(range)

// ---- margins, case ------------------------------------------------------------------------------------

public fun String.trimMargin(marginPrefix: String = "|"): String = replaceIndentByMargin("", marginPrefix)

public fun String.replaceIndentByMargin(newIndent: String = "", marginPrefix: String = "|"): String {
    require(marginPrefix.isNotBlank()) { "marginPrefix must be non-blank string." }
    val lines = lines()
    val lastIndex = lines.size - 1
    val sb = StringBuilder()
    var index = 0
    var first = true
    for (value in lines) {
        val i = index
        index++
        if ((i == 0 || i == lastIndex) && value.isBlank()) continue
        var line = value
        var start = 0
        while (start < value.length && value[start].isWhitespace()) start++
        if (start < value.length && value.startsWith(marginPrefix, start)) {
            line = value.substring(start + marginPrefix.length)
        }
        if (!first) sb.append('\n')
        first = false
        sb.append(newIndent).append(line)
    }
    return sb.toString()
}

public fun String.prependIndent(indent: String = "    "): String {
    val sb = StringBuilder()
    var first = true
    for (line in lines()) {
        if (!first) sb.append('\n')
        first = false
        if (line.isBlank()) {
            if (line.length < indent.length) sb.append(indent) else sb.append(line)
        } else {
            sb.append(indent).append(line)
        }
    }
    return sb.toString()
}

public fun String.capitalize(): String = if (length > 0 && this[0].isLowerCase()) this[0].titlecaseChar().toString() + substring(1) else this

public fun String.decapitalize(): String = if (length > 0 && !this[0].isLowerCase()) this[0].lowercaseChar().toString() + substring(1) else this

public inline fun String.replaceFirstChar(transform: (Char) -> Char): String =
    if (length > 0) transform(this[0]).toString() + substring(1) else this

@kotlin.jvm.JvmName("replaceFirstCharWithString")
public inline fun String.replaceFirstChar(transform: (Char) -> String): String =
    if (length > 0) transform(this[0]) + substring(1) else this

public fun Char.titlecaseChar(): Char {
    val c = this.code
    if (c in 0x1C4..0x1CC) return (if (c < 0x1C7) 0x1C5 else if (c < 0x1CA) 0x1C8 else 0x1CB).toChar()
    if (c in 0x1F1..0x1F3) return '\u01F2'
    return uppercaseChar()
}

public fun Char.titlecase(): String {
    val upper = uppercase()
    if (upper.length > 1) {
        return if (this == '\u0149') upper else upper[0].toString() + upper.substring(1).lowercase()
    }
    return titlecaseChar().toString()
}

public fun Char.isTitleCase(): Boolean = this.code >= 0x80 && charType(this.code) == 3


public fun Char.isISOControl(): Boolean = this.code <= 0x1F || (this.code >= 0x7F && this.code <= 0x9F)

public fun Char.isDefined(): Boolean = this.code < 0x80 || charType(this.code) != 0

public fun Char.isSurrogate(): Boolean = this.code in 0xD800..0xDFFF

public fun Char.isHighSurrogate(): Boolean = this.code in 0xD800..0xDBFF

public fun Char.isLowSurrogate(): Boolean = this.code in 0xDC00..0xDFFF

public operator fun Char.plus(other: String): String = this.toString() + other

// ---- windowed / zip -----------------------------------------------------------------------------------

public fun String.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false): List<String> =
    windowed(size, step, partialWindows) { it.toString() }

public fun <R> String.windowed(size: Int, step: Int = 1, partialWindows: Boolean = false, transform: (String) -> R): List<R> {
    require(size > 0 && step > 0) {
        if (size != step) "Both size " + size + " and step " + step + " must be greater than zero." else "size " + size + " must be greater than zero."
    }
    val thisSize = length
    val result = ArrayList<R>((thisSize + step - 1) / step)
    var index = 0
    while (index < thisSize) {
        val end = index + size
        val coercedEnd = if (end < 0 || end > thisSize) (if (partialWindows) thisSize else -1) else end
        if (coercedEnd < 0) break
        result.add(transform(substring(index, coercedEnd)))
        index += step
    }
    return result
}

public fun <R> String.chunked(size: Int, transform: (String) -> R): List<R> = windowed(size, size, true, transform)

public infix fun String.zip(other: String): List<Pair<Char, Char>> {
    val n = if (length < other.length) length else other.length
    val r = ArrayList<Pair<Char, Char>>(n)
    var i = 0
    while (i < n) {
        r.add(Pair(this[i], other[i]))
        i++
    }
    return r
}

public fun <V> String.zip(other: String, transform: (a: Char, b: Char) -> V): List<V> {
    val n = if (length < other.length) length else other.length
    val r = ArrayList<V>(n)
    var i = 0
    while (i < n) {
        r.add(transform(this[i], other[i]))
        i++
    }
    return r
}

public fun String.zipWithNext(): List<Pair<Char, Char>> {
    val r = ArrayList<Pair<Char, Char>>()
    var i = 0
    while (i + 1 < length) {
        r.add(Pair(this[i], this[i + 1]))
        i++
    }
    return r
}

public fun <R> String.zipWithNext(transform: (a: Char, b: Char) -> R): List<R> {
    val r = ArrayList<R>()
    var i = 0
    while (i + 1 < length) {
        r.add(transform(this[i], this[i + 1]))
        i++
    }
    return r
}

// ---- collection-like -------------------------------------------------------------------------------------

public fun String.count(): Int = length
public fun String.toSet(): Set<Char> = toList().toSet()
public fun String.toMutableList(): MutableList<Char> = toList().toMutableList()
public fun String.toHashSet(): HashSet<Char> = HashSet<Char>(toList())
public fun String.single(): Char {
    if (length == 0) throw NoSuchElementException("Char sequence is empty.")
    if (length != 1) throw IllegalArgumentException("Char sequence has more than one element.")
    return this[0]
}
public fun String.singleOrNull(): Char? = if (length == 1) this[0] else null
public fun String.getOrNull(index: Int): Char? = if (index >= 0 && index < length) this[index] else null
public inline fun String.getOrElse(index: Int, defaultValue: (Int) -> Char): Char = if (index >= 0 && index < length) this[index] else defaultValue(index)
public fun String.elementAt(index: Int): Char = this[index]
public fun String.elementAtOrNull(index: Int): Char? = getOrNull(index)
public inline fun String.find(predicate: (Char) -> Boolean): Char? = firstOrNull(predicate)
public inline fun String.findLast(predicate: (Char) -> Boolean): Char? {
    var i = length - 1
    while (i >= 0) {
        if (predicate(this[i])) return this[i]
        i--
    }
    return null
}
public inline fun String.filterNot(predicate: (Char) -> Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < length) {
        if (!predicate(this[i])) sb.append(this[i])
        i++
    }
    return sb.toString()
}
public inline fun String.filterIndexed(predicate: (index: Int, Char) -> Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < length) {
        if (predicate(i, this[i])) sb.append(this[i])
        i++
    }
    return sb.toString()
}
public inline fun <R : Any> String.mapNotNull(transform: (Char) -> R?): List<R> {
    val r = ArrayList<R>()
    var i = 0
    while (i < length) {
        val v = transform(this[i])
        if (v != null) r.add(v)
        i++
    }
    return r
}
public inline fun <R> String.flatMap(transform: (Char) -> Iterable<R>): List<R> {
    val r = ArrayList<R>()
    var i = 0
    while (i < length) {
        r.addAll(transform(this[i]))
        i++
    }
    return r
}
public inline fun <R> String.fold(initial: R, operation: (acc: R, Char) -> R): R {
    var acc = initial
    var i = 0
    while (i < length) {
        acc = operation(acc, this[i])
        i++
    }
    return acc
}
public inline fun String.reduce(operation: (acc: Char, Char) -> Char): Char {
    if (length == 0) throw UnsupportedOperationException("Empty char sequence can't be reduced.")
    var acc = this[0]
    var i = 1
    while (i < length) {
        acc = operation(acc, this[i])
        i++
    }
    return acc
}
public inline fun <K, V> String.associate(transform: (Char) -> Pair<K, V>): Map<K, V> {
    val m = LinkedHashMap<K, V>()
    var i = 0
    while (i < length) {
        val p = transform(this[i])
        m[p.first] = p.second
        i++
    }
    return m
}
public inline fun <K> String.associateBy(keySelector: (Char) -> K): Map<K, Char> {
    val m = LinkedHashMap<K, Char>()
    var i = 0
    while (i < length) {
        m[keySelector(this[i])] = this[i]
        i++
    }
    return m
}
public inline fun <V> String.associateWith(valueSelector: (Char) -> V): Map<Char, V> {
    val m = LinkedHashMap<Char, V>()
    var i = 0
    while (i < length) {
        m[this[i]] = valueSelector(this[i])
        i++
    }
    return m
}
public inline fun <K> String.groupBy(keySelector: (Char) -> K): Map<K, List<Char>> = toList().groupBy(keySelector)
public inline fun String.partition(predicate: (Char) -> Boolean): Pair<String, String> {
    val a = StringBuilder()
    val b = StringBuilder()
    var i = 0
    while (i < length) {
        if (predicate(this[i])) a.append(this[i]) else b.append(this[i])
        i++
    }
    return Pair(a.toString(), b.toString())
}
public fun String.maxOrNull(): Char? {
    if (length == 0) return null
    var m = this[0]
    var i = 1
    while (i < length) {
        if (this[i] > m) m = this[i]
        i++
    }
    return m
}
public fun String.minOrNull(): Char? {
    if (length == 0) return null
    var m = this[0]
    var i = 1
    while (i < length) {
        if (this[i] < m) m = this[i]
        i++
    }
    return m
}
public fun String.max(): Char = maxOrNull() ?: throw NoSuchElementException()
public fun String.min(): Char = minOrNull() ?: throw NoSuchElementException()
public inline fun <R : Comparable<R>> String.maxByOrNull(selector: (Char) -> R): Char? = toList().maxByOrNull(selector)
public inline fun <R : Comparable<R>> String.minByOrNull(selector: (Char) -> R): Char? = toList().minByOrNull(selector)
public inline fun String.onEach(action: (Char) -> Unit): String {
    var i = 0
    while (i < length) {
        action(this[i])
        i++
    }
    return this
}
public fun String.random(random: kotlin.random.Random): Char {
    if (length == 0) throw NoSuchElementException("Char sequence is empty.")
    return this[random.nextInt(length)]
}
public fun String.toFloatOrNull(): Float? = toDoubleOrNull()?.toFloat()
public fun String.toFloat(): Float = toDouble().toFloat()

// ---- deprecated case conversions, kept for source compatibility ----------------------------------------------------

public fun String.toUpperCase(): String = uppercase()

public fun String.toLowerCase(): String = lowercase()

public fun Char.toUpperCase(): Char = uppercaseChar()

public fun Char.toLowerCase(): Char = lowercaseChar()
