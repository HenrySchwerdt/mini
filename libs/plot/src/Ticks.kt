package mini.plot

import kotlin.math.abs

// Tick computation and number formatting. Everything here is local on purpose: mini.plot needs nothing but
// `abs`/`min`/`max` of the standard library (no kotlin.math: floor, ceil, pow and log10 are a few lines each).

/** `10^e` for an integer exponent. */
internal fun pow10(e: Int): Double {
    var p = 1.0
    var i = 0
    val n = if (e < 0) -e else e
    while (i < n) {
        p *= 10.0
        i++
    }
    return if (e < 0) 1.0 / p else p
}

/** The largest integer that is <= [x] (for |x| below 9e18), as a Double. */
internal fun floorOf(x: Double): Double {
    if (x != x || x > 9.0e18 || x < -9.0e18) return x
    val t = x.toLong().toDouble()
    return if (t > x) t - 1.0 else t
}

/** The smallest integer that is >= [x], as a Double. */
internal fun ceilOf(x: Double): Double {
    if (x != x || x > 9.0e18 || x < -9.0e18) return x
    val t = x.toLong().toDouble()
    return if (t < x) t + 1.0 else t
}

/** `floor(log10(x))` for x > 0. */
internal fun floorLog10(x: Double): Int {
    var y = x
    var e = 0
    if (y <= 0.0 || y != y || y == Double.POSITIVE_INFINITY) return 0
    while (y >= 10.0) {
        y /= 10.0
        e++
    }
    while (y < 1.0) {
        y *= 10.0
        e--
    }
    return e
}

/** [x] rounded to [decimals] digits after the decimal point (removes the noise of `0.1 * 3`). */
internal fun roundTo(x: Double, decimals: Int): Double {
    if (decimals > 12 || decimals < 0) return x
    val m = pow10(decimals)
    val s = x * m
    if (s > 9.0e18 || s < -9.0e18) return x
    val r = if (s < 0.0) -floorOf(-s + 0.5) else floorOf(s + 0.5)
    return r / m
}

/** A "nice" number close to [x]: 1, 2, 5 or 10 times a power of ten (rounded to the nearest when [round], else the next one up). */
public fun niceNumber(x: Double, round: Boolean): Double {
    if (x <= 0.0 || x != x) return 1.0
    val e = floorLog10(x)
    val f = x / pow10(e)
    val nf = if (round) {
        if (f < 1.5) 1.0 else if (f < 3.0) 2.0 else if (f < 7.0) 5.0 else 10.0
    } else {
        if (f <= 1.0) 1.0 else if (f <= 2.0) 2.0 else if (f <= 5.0) 5.0 else 10.0
    }
    return nf * pow10(e)
}

/** Axis ticks: the values (ascending, including both ends of the axis), the step between them and the digits after the point. */
public class Ticks(public val values: List<Double>, public val step: Double, public val decimals: Int) {
    public val first: Double get() = values[0]
    public val last: Double get() = values[values.size - 1]
}

/**
 * About [target] ticks that cover [lo]..[hi] with nice numbers; the first tick is <= [lo] and the last >= [hi].
 * A degenerate range (lo == hi, NaN or reversed) is widened around the value.
 */
public fun niceTicks(lo: Double, hi: Double, target: Int = 6): Ticks {
    var a = lo
    var b = hi
    if (a != a || b != b || a == Double.POSITIVE_INFINITY || b == Double.NEGATIVE_INFINITY) {
        a = 0.0
        b = 1.0
    }
    if (a > b) {
        val t = a
        a = b
        b = t
    }
    if (a == b) {
        val pad = if (a == 0.0) 1.0 else abs(a) * 0.1
        a -= pad
        b += pad
    }
    val count = if (target < 2) 2 else target
    val range = niceNumber(b - a, false)
    val step = niceNumber(range / (count - 1).toDouble(), true)
    val start = floorOf(a / step + 1.0e-9) * step
    val end = ceilOf(b / step - 1.0e-9) * step
    var decimals = 0
    if (step < 1.0) decimals = -floorLog10(step)
    if (decimals > 12) decimals = 12
    val values = ArrayList<Double>()
    var k = 0
    val n = ((end - start) / step + 0.5).toInt()
    while (k <= n) {
        values.add(roundTo(start + step * k.toDouble(), decimals))
        k++
    }
    return Ticks(values, step, decimals)
}

/** [v] with exactly [decimals] digits after the point (none for 0), e.g. `fixed(3.14159, 2) == "3.14"`. */
public fun fixed(v: Double, decimals: Int): String {
    if (v != v) return "NaN"
    if (v == Double.POSITIVE_INFINITY) return "inf"
    if (v == Double.NEGATIVE_INFINITY) return "-inf"
    val neg = v < 0.0
    val a = if (neg) -v else v
    if (a > 1.0e15) return (if (neg) "-" else "") + scientific(a, 3)
    val m = pow10(decimals)
    val scaled = (a * m + 0.5).toLong()
    val unit = m.toLong()
    val ip = scaled / unit
    val fp = scaled % unit
    val sb = StringBuilder()
    if (neg && scaled != 0L) sb.append('-')
    sb.append(ip)
    if (decimals > 0) {
        sb.append('.')
        val digits = fp.toString()
        var pad = decimals - digits.length
        while (pad > 0) {
            sb.append('0')
            pad--
        }
        sb.append(digits)
    }
    return sb.toString()
}

/** [v] in scientific notation with up to [digits] significant digits after the point: `1.5e7`, `-2e-5`. */
public fun scientific(v: Double, digits: Int = 2): String {
    if (v == 0.0) return "0"
    val neg = v < 0.0
    val a = if (neg) -v else v
    var e = floorLog10(a)
    var mant = roundTo(a / pow10(e), digits)
    if (mant >= 10.0) {
        mant /= 10.0
        e++
    }
    var s = fixed(mant, digits)
    s = trimZeros(s)
    return (if (neg) "-" else "") + s + "e" + e
}

/** The label of a tick: plain with the ticks' decimals, scientific for very large or very small axes. */
public fun tickLabel(v: Double, ticks: Ticks): String {
    val big = abs(ticks.first) >= 1.0e7 || abs(ticks.last) >= 1.0e7
    val small = ticks.decimals > 6
    if (big || small) return scientific(v, 2)
    return fixed(v, ticks.decimals)
}

/** A number for an SVG attribute: at most two decimals, no trailing zeros. */
internal fun num(v: Double): String {
    if (v != v) return "0"
    var x = v
    if (x > 1.0e9) x = 1.0e9
    if (x < -1.0e9) x = -1.0e9
    var s = fixed(x, 2)
    s = trimZeros(s)
    return if (s == "-0") "0" else s
}

/** "1.500" -> "1.5", "2.00" -> "2", "100" stays. */
internal fun trimZeros(s: String): String {
    if (!s.contains('.')) return s
    var end = s.length
    while (end > 0 && s[end - 1] == '0') end--
    if (end > 0 && s[end - 1] == '.') end--
    return s.substring(0, end)
}

internal fun dmax(a: Double, b: Double): Double = if (a >= b) a else b

internal fun dmin(a: Double, b: Double): Double = if (a <= b) a else b

/** Escapes text for use in XML text and attribute values. */
internal fun xmlEscape(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '&') sb.append("&amp;")
        else if (c == '<') sb.append("&lt;")
        else if (c == '>') sb.append("&gt;")
        else if (c == '"') sb.append("&quot;")
        else if (c == '\'') sb.append("&#39;")
        else sb.append(c)
        i++
    }
    return sb.toString()
}
