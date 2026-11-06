/*
 * Mini stdlib: the long tail of kotlin.text (string / char sequence operators, number parsing); the semantics, including the
 * exceptions, follow kotlin-stdlib.
 */
package kotlin.text

// ---- windows of char sequences -------------------------------------------------------------------------

public fun CharSequence.chunkedSequence(size: Int): Sequence<String> = windowedSequence(size, size, true)

public fun <R> CharSequence.chunkedSequence(size: Int, transform: (CharSequence) -> R): Sequence<R> = windowedSequence(size, size, true, transform)

public fun CharSequence.windowedSequence(size: Int, step: Int = 1, partialWindows: Boolean = false): Sequence<String> =
    windowedSequence(size, step, partialWindows) { it.toString() }

public fun <R> CharSequence.windowedSequence(size: Int, step: Int = 1, partialWindows: Boolean = false, transform: (CharSequence) -> R): Sequence<R> {
    kotlin.collections.checkWindowSizeStep(size, step)
    val cs = this
    return Sequence<R> {
        var index = 0
        object : Iterator<R> {
            override fun hasNext(): Boolean = index < cs.length && (partialWindows || index + size <= cs.length)
            override fun next(): R {
                if (!hasNext()) throw NoSuchElementException()
                val end = index + size
                val r = transform(cs.subSequence(index, if (end > cs.length) cs.length else end))
                index += step
                return r
            }
        }
    }
}

// ---- trimming, searching ---------------------------------------------------------------------------------

public inline fun String.trimStart(predicate: (Char) -> Boolean): String {
    var index = 0
    while (index < length) {
        if (!predicate(this[index])) return substring(index)
        index++
    }
    return ""
}

public inline fun String.trimEnd(predicate: (Char) -> Boolean): String {
    var index = length - 1
    while (index >= 0) {
        if (!predicate(this[index])) return substring(0, index + 1)
        index--
    }
    return ""
}

public fun String.trim(vararg chars: Char): String = trim { it in chars }
public fun String.trimStart(vararg chars: Char): String = trimStart { it in chars }
public fun String.trimEnd(vararg chars: Char): String = trimEnd { it in chars }
public fun CharSequence.trim(vararg chars: Char): CharSequence = trim { it in chars }
public fun CharSequence.trimStart(vararg chars: Char): CharSequence = trimStart { it in chars }
public fun CharSequence.trimEnd(vararg chars: Char): CharSequence = trimEnd { it in chars }

public fun CharSequence.lastIndexOfAny(chars: CharArray, startIndex: Int = lastIndex, ignoreCase: Boolean = false): Int {
    var index = if (startIndex < lastIndex) startIndex else lastIndex
    while (index >= 0) {
        val c = this[index]
        for (ch in chars) if (ch.equals(c, ignoreCase)) return index
        index--
    }
    return -1
}

public fun CharSequence.findLastAnyOf(strings: Collection<String>, startIndex: Int = lastIndex, ignoreCase: Boolean = false): Pair<Int, String>? {
    var index = if (startIndex < lastIndex) startIndex else lastIndex
    while (index >= 0) {
        for (s in strings) {
            if (s.regionMatches(0, this, index, s.length, ignoreCase)) return Pair(index, s)
        }
        index--
    }
    return null
}

public fun CharSequence.lastIndexOfAny(strings: Collection<String>, startIndex: Int = lastIndex, ignoreCase: Boolean = false): Int =
    findLastAnyOf(strings, startIndex, ignoreCase)?.first ?: -1

public fun Regex.splitToSequence(input: CharSequence, limit: Int = 0): Sequence<String> = split(input.toString(), limit).asSequence()

// ---- ranges of chars and bytes -----------------------------------------------------------------------------

private fun checkBoundsIndexes(startIndex: Int, endIndex: Int, size: Int) {
    if (startIndex < 0 || endIndex > size) throw IndexOutOfBoundsException("startIndex: " + startIndex + ", endIndex: " + endIndex + ", size: " + size)
    if (startIndex > endIndex) throw IllegalArgumentException("startIndex: " + startIndex + " > endIndex: " + endIndex)
}

public fun String.toCharArray(startIndex: Int, endIndex: Int = length): CharArray {
    checkBoundsIndexes(startIndex, endIndex, length)
    val r = CharArray(endIndex - startIndex)
    var i = startIndex
    while (i < endIndex) { r[i - startIndex] = this[i]; i++ }
    return r
}

public fun String.encodeToByteArray(startIndex: Int, endIndex: Int = length, throwOnInvalidSequence: Boolean = false): ByteArray {
    checkBoundsIndexes(startIndex, endIndex, length)
    return substring(startIndex, endIndex).encodeToByteArray()
}

// ---- number parsing ----------------------------------------------------------------------------------------

public fun String.toLong(radix: Int): Long = toLongOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toByteOrNull(): Byte? = toByteOrNull(10)

public fun String.toByteOrNull(radix: Int): Byte? {
    val int = this.toIntOrNull(radix) ?: return null
    if (int < Byte.MIN_VALUE || int > Byte.MAX_VALUE) return null
    return int.toByte()
}

public fun String.toByte(): Byte = toByteOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toByte(radix: Int): Byte = toByteOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toShortOrNull(): Short? = toShortOrNull(10)

public fun String.toShortOrNull(radix: Int): Short? {
    val int = this.toIntOrNull(radix) ?: return null
    if (int < Short.MIN_VALUE || int > Short.MAX_VALUE) return null
    return int.toShort()
}

public fun String.toShort(): Short = toShortOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toShort(radix: Int): Short = toShortOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toBooleanStrict(): Boolean =
    if (this == "true") true else if (this == "false") false else throw IllegalArgumentException("The string doesn't represent a boolean value: " + this)

public fun String.toUByteOrNull(): UByte? = toUByteOrNull(10)

public fun String.toUByteOrNull(radix: Int): UByte? {
    val int = this.toUIntOrNull(radix) ?: return null
    if (int > UByte.MAX_VALUE.toUInt()) return null
    return int.toUByte()
}

public fun String.toUByte(): UByte = toUByteOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toUByte(radix: Int): UByte = toUByteOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toUShortOrNull(): UShort? = toUShortOrNull(10)

public fun String.toUShortOrNull(radix: Int): UShort? {
    val int = this.toUIntOrNull(radix) ?: return null
    if (int > UShort.MAX_VALUE.toUInt()) return null
    return int.toUShort()
}

public fun String.toUShort(): UShort = toUShortOrNull() ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toUShort(radix: Int): UShort = toUShortOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toUIntOrNull(radix: Int): UInt? {
    val l = toULongOrNull(radix) ?: return null
    return if (l > UInt.MAX_VALUE.toULong()) null else l.toUInt()
}

public fun String.toUInt(radix: Int): UInt = toUIntOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

public fun String.toULongOrNull(radix: Int): ULong? {
    val length = this.length
    if (length == 0) return null
    val first = this[0]
    var start = 0
    if (first < '0') {
        if (length == 1 || first != '+') return null
        start = 1
    }
    val limitBeforeMul = ulongDivide(-1L, radix.toLong())
    var result = 0L
    var i = start
    while (i < length) {
        val c = this[i]
        val digit = if (c in '0'..'9') c.code - '0'.code
        else if (c in 'a'..'z') c.code - 'a'.code + 10
        else if (c in 'A'..'Z') c.code - 'A'.code + 10
        else -1
        if (digit < 0 || digit >= radix) return null
        if ((result xor Long.MIN_VALUE) > (limitBeforeMul xor Long.MIN_VALUE)) return null
        result *= radix.toLong()
        val beforeAdding = result
        result += digit.toLong()
        if ((result xor Long.MIN_VALUE) < (beforeAdding xor Long.MIN_VALUE)) return null
        i++
    }
    return ULong(result)
}

public fun String.toULong(radix: Int): ULong = toULongOrNull(radix) ?: mini.internal.throwNumberFormat("For input string: \"$this\"")

// ---- CharSequence: selecting, folding ----------------------------------------------------------------------

public fun CharSequence.random(): Char = random(kotlin.random.Random.Default)

public fun CharSequence.randomOrNull(): Char? = if (isEmpty()) null else this[kotlin.random.Random.Default.nextInt(length)]

public fun CharSequence.randomOrNull(random: kotlin.random.Random): Char? = if (isEmpty()) null else this[random.nextInt(length)]

public inline fun <R, C : MutableCollection<in R>> CharSequence.mapTo(destination: C, transform: (Char) -> R): C {
    var i = 0
    while (i < length) { destination.add(transform(this[i])); i++ }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> CharSequence.mapIndexedTo(destination: C, transform: (index: Int, Char) -> R): C {
    var i = 0
    while (i < length) { destination.add(transform(i, this[i])); i++ }
    return destination
}

public inline fun <R : Any, C : MutableCollection<in R>> CharSequence.mapNotNullTo(destination: C, transform: (Char) -> R?): C {
    var i = 0
    while (i < length) { val v = transform(this[i]); if (v != null) destination.add(v); i++ }
    return destination
}

public inline fun <R, C : MutableCollection<in R>> CharSequence.flatMapTo(destination: C, transform: (Char) -> Iterable<R>): C {
    var i = 0
    while (i < length) { destination.addAll(transform(this[i])); i++ }
    return destination
}

public inline fun <C : StringBuilder> CharSequence.filterTo(destination: C, predicate: (Char) -> Boolean): C {
    var i = 0
    while (i < length) { val c = this[i]; if (predicate(c)) destination.append(c); i++ }
    return destination
}

public inline fun <C : StringBuilder> CharSequence.filterNotTo(destination: C, predicate: (Char) -> Boolean): C {
    var i = 0
    while (i < length) { val c = this[i]; if (!predicate(c)) destination.append(c); i++ }
    return destination
}

public fun <C : MutableCollection<in Char>> CharSequence.toCollection(destination: C): C {
    var i = 0
    while (i < length) { destination.add(this[i]); i++ }
    return destination
}

public inline fun <R : Any> CharSequence.firstNotNullOfOrNull(transform: (Char) -> R?): R? {
    var i = 0
    while (i < length) { val v = transform(this[i]); if (v != null) return v; i++ }
    return null
}

public inline fun <R : Any> CharSequence.firstNotNullOf(transform: (Char) -> R?): R =
    firstNotNullOfOrNull(transform) ?: throw NoSuchElementException("No element of the char sequence was transformed to a non-null value.")

public inline fun CharSequence.sumBy(selector: (Char) -> Int): Int {
    var sum = 0
    var i = 0
    while (i < length) { sum += selector(this[i]); i++ }
    return sum
}

public inline fun CharSequence.sumByDouble(selector: (Char) -> Double): Double {
    var sum = 0.0
    var i = 0
    while (i < length) { sum += selector(this[i]); i++ }
    return sum
}

public inline fun CharSequence.reduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): Char {
    if (isEmpty()) throw UnsupportedOperationException("Empty char sequence can't be reduced.")
    var accumulator = this[0]
    var index = 1
    while (index < length) { accumulator = operation(index, accumulator, this[index]); index++ }
    return accumulator
}

public inline fun CharSequence.reduceOrNull(operation: (acc: Char, Char) -> Char): Char? {
    if (isEmpty()) return null
    var accumulator = this[0]
    var index = 1
    while (index < length) { accumulator = operation(accumulator, this[index]); index++ }
    return accumulator
}

public inline fun CharSequence.reduceIndexedOrNull(operation: (index: Int, acc: Char, Char) -> Char): Char? {
    if (isEmpty()) return null
    var accumulator = this[0]
    var index = 1
    while (index < length) { accumulator = operation(index, accumulator, this[index]); index++ }
    return accumulator
}

public inline fun CharSequence.reduceRight(operation: (Char, acc: Char) -> Char): Char {
    var index = length - 1
    if (index < 0) throw UnsupportedOperationException("Empty char sequence can't be reduced.")
    var accumulator = this[index--]
    while (index >= 0) { accumulator = operation(this[index], accumulator); index-- }
    return accumulator
}

public inline fun CharSequence.reduceRightOrNull(operation: (Char, acc: Char) -> Char): Char? {
    var index = length - 1
    if (index < 0) return null
    var accumulator = this[index--]
    while (index >= 0) { accumulator = operation(this[index], accumulator); index-- }
    return accumulator
}

public inline fun <R> CharSequence.foldRight(initial: R, operation: (Char, acc: R) -> R): R {
    var index = length - 1
    var accumulator = initial
    while (index >= 0) { accumulator = operation(this[index], accumulator); index-- }
    return accumulator
}

public inline fun <R> CharSequence.foldRightIndexed(initial: R, operation: (index: Int, Char, acc: R) -> R): R {
    var index = length - 1
    var accumulator = initial
    while (index >= 0) { accumulator = operation(index, this[index], accumulator); index-- }
    return accumulator
}

public inline fun <R> CharSequence.runningFold(initial: R, operation: (acc: R, Char) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(length + 1)
    result.add(initial)
    var accumulator = initial
    var i = 0
    while (i < length) { accumulator = operation(accumulator, this[i]); result.add(accumulator); i++ }
    return result
}

public inline fun <R> CharSequence.scan(initial: R, operation: (acc: R, Char) -> R): List<R> = runningFold(initial, operation)

public inline fun <R> CharSequence.runningFoldIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R> {
    if (isEmpty()) return listOf(initial)
    val result = ArrayList<R>(length + 1)
    result.add(initial)
    var accumulator = initial
    var i = 0
    while (i < length) { accumulator = operation(i, accumulator, this[i]); result.add(accumulator); i++ }
    return result
}

public inline fun <R> CharSequence.scanIndexed(initial: R, operation: (index: Int, acc: R, Char) -> R): List<R> = runningFoldIndexed(initial, operation)

public inline fun CharSequence.runningReduce(operation: (acc: Char, Char) -> Char): List<Char> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<Char>(length)
    result.add(accumulator)
    var index = 1
    while (index < length) { accumulator = operation(accumulator, this[index]); result.add(accumulator); index++ }
    return result
}

public inline fun CharSequence.runningReduceIndexed(operation: (index: Int, acc: Char, Char) -> Char): List<Char> {
    if (isEmpty()) return emptyList()
    var accumulator = this[0]
    val result = ArrayList<Char>(length)
    result.add(accumulator)
    var index = 1
    while (index < length) { accumulator = operation(index, accumulator, this[index]); result.add(accumulator); index++ }
    return result
}

// ---- CharSequence: extremes --------------------------------------------------------------------------------

public inline fun <R : Comparable<R>> CharSequence.maxOf(selector: (Char) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var maxValue = selector(this[0])
    var i = 1
    while (i < length) { val v = selector(this[i]); if (maxValue < v) maxValue = v; i++ }
    return maxValue
}

public inline fun <R : Comparable<R>> CharSequence.minOf(selector: (Char) -> R): R {
    if (isEmpty()) throw NoSuchElementException()
    var minValue = selector(this[0])
    var i = 1
    while (i < length) { val v = selector(this[i]); if (minValue > v) minValue = v; i++ }
    return minValue
}

public inline fun <R : Comparable<R>> CharSequence.maxOfOrNull(selector: (Char) -> R): R? {
    if (isEmpty()) return null
    var maxValue = selector(this[0])
    var i = 1
    while (i < length) { val v = selector(this[i]); if (maxValue < v) maxValue = v; i++ }
    return maxValue
}

public inline fun <R : Comparable<R>> CharSequence.minOfOrNull(selector: (Char) -> R): R? {
    if (isEmpty()) return null
    var minValue = selector(this[0])
    var i = 1
    while (i < length) { val v = selector(this[i]); if (minValue > v) minValue = v; i++ }
    return minValue
}

public inline fun <R : Comparable<R>> CharSequence.maxByOrNull(selector: (Char) -> R): Char? {
    if (isEmpty()) return null
    var maxElem = this[0]
    var maxValue = selector(maxElem)
    var i = 1
    while (i < length) {
        val e = this[i]
        val v = selector(e)
        if (maxValue < v) { maxElem = e; maxValue = v }
        i++
    }
    return maxElem
}

public inline fun <R : Comparable<R>> CharSequence.minByOrNull(selector: (Char) -> R): Char? {
    if (isEmpty()) return null
    var minElem = this[0]
    var minValue = selector(minElem)
    var i = 1
    while (i < length) {
        val e = this[i]
        val v = selector(e)
        if (minValue > v) { minElem = e; minValue = v }
        i++
    }
    return minElem
}

public inline fun <R : Comparable<R>> CharSequence.maxBy(selector: (Char) -> R): Char = maxByOrNull(selector) ?: throw NoSuchElementException()

public inline fun <R : Comparable<R>> CharSequence.minBy(selector: (Char) -> R): Char = minByOrNull(selector) ?: throw NoSuchElementException()

public fun CharSequence.maxWithOrNull(comparator: Comparator<in Char>): Char? {
    if (isEmpty()) return null
    var max = this[0]
    var i = 1
    while (i < length) { val e = this[i]; if (comparator.compare(max, e) < 0) max = e; i++ }
    return max
}

public fun CharSequence.minWithOrNull(comparator: Comparator<in Char>): Char? {
    if (isEmpty()) return null
    var min = this[0]
    var i = 1
    while (i < length) { val e = this[i]; if (comparator.compare(min, e) > 0) min = e; i++ }
    return min
}

public fun CharSequence.maxWith(comparator: Comparator<in Char>): Char = maxWithOrNull(comparator) ?: throw NoSuchElementException()

public fun CharSequence.minWith(comparator: Comparator<in Char>): Char = minWithOrNull(comparator) ?: throw NoSuchElementException()

@kotlin.jvm.JvmName("sumOfLongChars")
public inline fun CharSequence.sumOf(selector: (Char) -> Long): Long {
    var sum = 0L
    var i = 0
    while (i < length) { sum += selector(this[i]); i++ }
    return sum
}

@kotlin.jvm.JvmName("sumOfDoubleChars")
public inline fun CharSequence.sumOf(selector: (Char) -> Double): Double {
    var sum = 0.0
    var i = 0
    while (i < length) { sum += selector(this[i]); i++ }
    return sum
}

public val String.Companion.CASE_INSENSITIVE_ORDER: Comparator<String>
    get() = Comparator { a, b -> a.compareTo(b, true) }

public fun StringBuilder(content: CharSequence): StringBuilder = StringBuilder(content.toString())
