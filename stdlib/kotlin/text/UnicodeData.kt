/*
 * Mini stdlib: the lookups over UnicodeTables.kt (general category, simple case mappings, full upper case mappings) for the
 * Basic Multilingual Plane. The tables are decoded on the first use of a non-ASCII character; ASCII never gets here.
 */
package kotlin.text

private var catStarts: IntArray? = null
private var catCodes: IntArray? = null
private var upStarts: IntArray? = null
private var upDeltas: IntArray? = null
private var loStarts: IntArray? = null
private var loDeltas: IntArray? = null
private var otherUpper: IntArray? = null
private var otherLower: IntArray? = null
private var fullUpperCodes: IntArray? = null
private var fullUpperOffsets: IntArray? = null
private var fullUpperCount: Int = 0

private fun hexAt(s: String, i: Int, n: Int): Int {
    var v = 0
    var k = 0
    while (k < n) {
        val c = s[i + k].code
        v = v * 16 + (if (c <= 57) c - 48 else c - 55)
        k++
    }
    return v
}

private fun decodeCategories() {
    val s = UnicodeTables.CATEGORIES
    val n = s.length / 5
    val starts = IntArray(n)
    val codes = IntArray(n)
    var i = 0
    while (i < n) {
        starts[i] = hexAt(s, i * 5, 4)
        codes[i] = s[i * 5 + 4].code - 48
        i++
    }
    catStarts = starts
    catCodes = codes
}

private fun decodeDeltas(s: String, isUpper: Boolean) {
    val n = s.length / 8
    val starts = IntArray(n)
    val deltas = IntArray(n)
    var i = 0
    while (i < n) {
        starts[i] = hexAt(s, i * 8, 4)
        val d = hexAt(s, i * 8 + 4, 4)
        deltas[i] = if (d >= 32768) d - 65536 else d
        i++
    }
    if (isUpper) {
        upStarts = starts
        upDeltas = deltas
    } else {
        loStarts = starts
        loDeltas = deltas
    }
}

private fun decodeRanges(s: String): IntArray {
    val n = s.length / 8
    val r = IntArray(n * 2)
    var i = 0
    while (i < n) {
        r[i * 2] = hexAt(s, i * 8, 4)
        r[i * 2 + 1] = hexAt(s, i * 8 + 4, 4)
        i++
    }
    return r
}

/** the index of the last element of [a] that is <= [c] */
private fun lastAtMost(a: IntArray, c: Int): Int {
    var lo = 0
    var hi = a.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (a[mid] <= c) lo = mid else hi = mid - 1
    }
    return lo
}

private fun inRanges(r: IntArray, c: Int): Boolean {
    var lo = 0
    var hi = r.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (c < r[mid * 2]) hi = mid - 1
        else if (c > r[mid * 2 + 1]) lo = mid + 1
        else return true
    }
    return false
}

/** The general category of the character as java.lang.Character.getType numbers it (Lu = 1, Ll = 2, Nd = 9, Zs = 12, ...). */
internal fun charType(c: Int): Int {
    if (catStarts == null) decodeCategories()
    return catCodes!![lastAtMost(catStarts!!, c)]
}

/** The value of a decimal digit (Nd) of any script: 0..9. */
internal fun unicodeDigitValue(c: Int): Int {
    if (catStarts == null) decodeCategories()
    val starts = catStarts!!
    val i = lastAtMost(starts, c)
    return (c - starts[i]) % 10
}

internal fun simpleUpperCase(c: Int): Int {
    if (upStarts == null) decodeDeltas(UnicodeTables.UPPER, true)
    return c + upDeltas!![lastAtMost(upStarts!!, c)]
}

internal fun simpleLowerCase(c: Int): Int {
    if (loStarts == null) decodeDeltas(UnicodeTables.LOWER, false)
    return c + loDeltas!![lastAtMost(loStarts!!, c)]
}

internal fun isOtherUpperCase(c: Int): Boolean {
    if (otherUpper == null) otherUpper = decodeRanges(UnicodeTables.OTHER_UPPER)
    return inRanges(otherUpper!!, c)
}

internal fun isOtherLowerCase(c: Int): Boolean {
    if (otherLower == null) otherLower = decodeRanges(UnicodeTables.OTHER_LOWER)
    return inRanges(otherLower!!, c)
}

/** The full upper case mapping of [c] when it has more than one character (`ß` -> `SS`), else null. */
internal fun fullUpperCase(c: Int): String? {
    if (fullUpperCodes == null) {
        val s = UnicodeTables.FULL_UPPER
        var n = 0
        var j = 0
        while (j < s.length) {
            if (s[j] == ';') n++
            j++
        }
        val codes = IntArray(n + 1)
        val offs = IntArray(n + 1)
        var k = 0
        var i = 0
        while (i < s.length) {
            codes[k] = hexAt(s, i, 4)
            i += 5
            offs[k] = i
            while (i < s.length && s[i] != ';') i++
            k++
            if (i < s.length) i++
        }
        fullUpperOffsets = offs
        fullUpperCount = k
        fullUpperCodes = codes
    }
    val codes = fullUpperCodes!!
    var k = 0
    while (k < fullUpperCount) {
        if (codes[k] == c) {
            val s = UnicodeTables.FULL_UPPER
            var i = fullUpperOffsets!![k]
            val sb = StringBuilder()
            while (i < s.length && s[i] != ';') {
                sb.append(hexAt(s, i, 4).toChar())
                i += 4
                if (i < s.length && s[i] == ' ') i++
            }
            return sb.toString()
        }
        k++
    }
    return null
}
