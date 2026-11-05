/*
 * Mini stdlib: `Char(code)` and the UTF-8 conversions between String and ByteArray.
 */
package kotlin

import kotlin.text.*

/** `Char(code)`: the character with the given UTF-16 code (0..65535). */
public fun Char(code: Int): Char {
    if (code < 0 || code > 65535) mini.internal.throwIllegalArgument("Invalid Char code: $code")
    return code.toChar()
}

/** The UTF-8 encoding of this string; unpaired surrogates become `?` (as in kotlin-stdlib). */
public fun String.encodeToByteArray(): ByteArray {
    var n = 0
    var i = 0
    while (i < length) {
        val c = this[i].code
        if (c < 0x80) n += 1
        else if (c < 0x800) n += 2
        else if (c in 0xD800..0xDBFF && i + 1 < length && this[i + 1].code in 0xDC00..0xDFFF) { n += 4; i++ }
        else if (c in 0xD800..0xDFFF) n += 1
        else n += 3
        i++
    }
    val out = ByteArray(n)
    var o = 0
    i = 0
    while (i < length) {
        val c = this[i].code
        if (c < 0x80) {
            out[o++] = c.toByte()
        } else if (c < 0x800) {
            out[o++] = (0xC0 or (c shr 6)).toByte()
            out[o++] = (0x80 or (c and 0x3F)).toByte()
        } else if (c in 0xD800..0xDBFF && i + 1 < length && this[i + 1].code in 0xDC00..0xDFFF) {
            val cp = 0x10000 + ((c - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
            out[o++] = (0xF0 or (cp shr 18)).toByte()
            out[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
            out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
            out[o++] = (0x80 or (cp and 0x3F)).toByte()
            i++
        } else if (c in 0xD800..0xDFFF) {
            out[o++] = '?'.code.toByte()
        } else {
            out[o++] = (0xE0 or (c shr 12)).toByte()
            out[o++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
            out[o++] = (0x80 or (c and 0x3F)).toByte()
        }
        i++
    }
    return out
}

public fun String.toByteArray(): ByteArray = encodeToByteArray()

/** Decodes UTF-8; malformed input becomes U+FFFD. */
public fun ByteArray.decodeToString(): String = decodeToString(0, size)

public fun ByteArray.decodeToString(startIndex: Int, endIndex: Int): String {
    val sb = StringBuilder()
    var i = startIndex
    while (i < endIndex) {
        val b0 = this[i].toInt() and 0xFF
        var need = 0
        var cp = 0
        if (b0 < 0x80) {
            cp = b0
        } else if (b0 in 0xC2..0xDF) {
            need = 1
            cp = b0 and 0x1F
        } else if (b0 in 0xE0..0xEF) {
            need = 2
            cp = b0 and 0x0F
        } else if (b0 in 0xF0..0xF4) {
            need = 3
            cp = b0 and 0x07
        } else {
            sb.append('�')
            i++
            continue
        }
        var j = 1
        var ok = true
        while (j <= need) {
            if (i + j >= endIndex) { ok = false; break }
            val b = this[i + j].toInt() and 0xFF
            if ((b and 0xC0) != 0x80) { ok = false; break }
            cp = (cp shl 6) or (b and 0x3F)
            j++
        }
        if (!ok || (need == 2 && (cp < 0x800 || cp in 0xD800..0xDFFF)) || (need == 3 && (cp < 0x10000 || cp > 0x10FFFF))) {
            sb.append('�')
            i += if (ok) need + 1 else if (j > 1) j else 1
            continue
        }
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        } else {
            sb.append(cp.toChar())
        }
        i += need + 1
    }
    return sb.toString()
}
