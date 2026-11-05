/*
 * String / Char factory functions.
 */
package kotlin.text


public fun String(chars: CharArray): String = chars.concatToString()

public fun String(chars: CharArray, offset: Int, length: Int): String {
    val sb = StringBuilder()
    var i = offset
    while (i < offset + length) {
        sb.append(chars[i])
        i++
    }
    return sb.toString()
}

public fun CharArray.concatToString(startIndex: Int, endIndex: Int): String = String(this, startIndex, endIndex - startIndex)

public fun Char(code: Int): Char {
    if (code < 0 || code > 0xFFFF) throw IllegalArgumentException("Invalid Char code: " + code)
    return code.toChar()
}
