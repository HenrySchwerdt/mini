/*
 * The code point functions of java.lang.String that Kotlin programs for the JVM call on a String (UTF-16 units, surrogate pairs combine).
 */
package kotlin.text

public fun String.codePointAt(index: Int): Int {
    if (index < 0 || index >= length) throw IndexOutOfBoundsException("index $index, length $length")
    val c = this[index]
    if (c.isHighSurrogate() && index + 1 < length) {
        val d = this[index + 1]
        if (d.isLowSurrogate()) return ((c.code - 0xD800) shl 10) + (d.code - 0xDC00) + 0x10000
    }
    return c.code
}

public fun String.codePointCount(beginIndex: Int, endIndex: Int): Int {
    var n = 0
    var i = beginIndex
    while (i < endIndex) {
        val c = this[i]
        i += if (c.isHighSurrogate() && i + 1 < endIndex && this[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}
