package mini.plot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Text metrics, tick label formatting, logarithmic ticks and colour maps.

internal fun isFin(v: Double): Boolean = v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY

/** The width of [s] in system-ui at [size] px, estimated per character class (good enough to reserve room for labels). */
internal fun textWidth(s: String, size: Double): Double {
    var w = 0.0
    for (c in s) {
        w += if (c == ' ' || c == '.' || c == ',' || c == ':' || c == ';' || c == '\'' || c == '!' || c == '|' || c == 'i' || c == 'j' || c == 'l' || c == 'I') 0.29
        else if (c == 't' || c == 'f' || c == 'r' || c == '-' || c == '(' || c == ')' || c == '/') 0.36
        else if (c == 'm' || c == 'w' || c == 'M' || c == 'W' || c == '@' || c == '%') 0.86
        else if (c >= 'A' && c <= 'Z') 0.66
        else if (c >= '0' && c <= '9') 0.57
        else 0.54
    }
    return w * size
}

/** [v] for a tooltip: at most 4 significant digits. */
internal fun shortNum(v: Double): String {
    if (!isFin(v)) return if (v != v) "NaN" else if (v > 0.0) "inf" else "-inf"
    val a = abs(v)
    if (a == 0.0) return "0"
    if (a >= 1.0e6 || a < 1.0e-4) return scientific(v, 3)
    val e = floorLog10(a)
    var decimals = 3 - e
    if (decimals < 0) decimals = 0
    if (decimals > 8) decimals = 8
    return trimZeros(fixed(v, decimals))
}

/** The digits after the point that [step] needs: 0.025 -> 3, 2.5 -> 1, 5 -> 0. */
internal fun decimalsFor(step: Double): Int {
    var d = 0
    var s = abs(step)
    if (s == 0.0 || !isFin(s)) return 0
    while (d < 8 && abs(s - floor(s + 0.5)) > 1.0e-7 * (if (s > 1.0) s else 1.0)) {
        s *= 10.0
        d++
    }
    return d
}

/** `1.5k`, `2M`, `3.2B`: [v] with an SI-like suffix from 1000 on; [step] (the tick step) sets the digits. */
internal fun siLabel(v: Double, step: Double): String {
    val a = abs(v)
    var unit = 1.0
    var suffix = ""
    if (a >= 1.0e12) {
        unit = 1.0e12
        suffix = "T"
    } else if (a >= 1.0e9) {
        unit = 1.0e9
        suffix = "B"
    } else if (a >= 1.0e6) {
        unit = 1.0e6
        suffix = "M"
    } else if (a >= 1000.0) {
        unit = 1000.0
        suffix = "k"
    }
    var decimals = decimalsFor(step / unit)
    if (decimals > 6) decimals = 6
    return trimZeros(fixed(v / unit, decimals)) + suffix
}

/** `25%` for 0.25; [step] is the tick step in the unit of [v] (a fraction). */
internal fun percentLabel(v: Double, step: Double): String {
    var decimals = decimalsFor(step * 100.0)
    if (decimals > 4) decimals = 4
    return trimZeros(fixed(v * 100.0, decimals)) + "%"
}

/** The labels of [ticks] in a format: "auto" (SI suffixes from 1000 on), "plain", "si", "percent", "sci". */
internal fun formatTicks(values: List<Double>, ticks: Ticks, format: String): List<String> {
    val out = ArrayList<String>()
    var big = 0.0
    for (v in values) if (abs(v) > big) big = abs(v)
    for (v in values) {
        if (format == "percent") out.add(percentLabel(v, ticks.step))
        else if (format == "sci") out.add(scientific(v, 2))
        else if (format == "plain") out.add(tickLabel(v, ticks))
        else if (format == "si" || big >= 1000.0) {
            if (ticks.decimals > 6) out.add(scientific(v, 2)) else out.add(siLabel(v, ticks.step))
        } else out.add(tickLabel(v, ticks))
    }
    return out
}

/** Ticks of a logarithmic axis between [lo] and [hi] (both > 0, decades): every power of ten, or 1/2/5 when the range is short. */
internal fun logTickValues(lo: Double, hi: Double): List<Double> {
    val out = ArrayList<Double>()
    val e0 = floor(log10(lo) + 1.0e-9).toInt()
    val e1 = ceil(log10(hi) - 1.0e-9).toInt()
    val dense = e1 - e0 < 3
    var e = e0
    while (e <= e1) {
        val p = 10.0.pow(e.toDouble())
        out.add(roundTo(p, if (e < 0) -e else 0))
        if (dense && e < e1) {
            out.add(roundTo(2.0 * p, if (e < 0) -e else 0))
            out.add(roundTo(5.0 * p, if (e < 0) -e else 0))
        }
        e++
    }
    return out
}

internal fun logLabel(v: Double): String {
    if (v >= 1000.0) return siLabel(v, 1000.0)
    if (v >= 1.0) return trimZeros(fixed(v, 0))
    if (v >= 1.0e-6) return trimZeros(fixed(v, -floorLog10(v)))
    return scientific(v, 1)
}

// ---- colour maps ----

private fun cmapStops(name: String): List<String> {
    if (name == "magma") return listOf("#000004", "#1d1147", "#51127c", "#822681", "#b73779", "#e75263", "#fc8961", "#fec287", "#fcfdbf")
    if (name == "plasma") return listOf("#0d0887", "#46039f", "#7201a8", "#9c179e", "#bd3786", "#d8576b", "#ed7953", "#fb9f3a", "#fdca26", "#f0f921")
    if (name == "coolwarm") return listOf("#3b4cc0", "#6788ee", "#9abbff", "#c9d7f0", "#dddddd", "#f2cbb7", "#f7a889", "#e26952", "#b40426")
    if (name == "gray" || name == "grey") return listOf("#000000", "#ffffff")
    return listOf("#440154", "#472d7b", "#3b528b", "#2c728e", "#21918c", "#28ae80", "#5ec962", "#addc30", "#fde725")
}

/** The names of the colour maps. */
internal val COLORMAPS: List<String> = listOf("viridis", "magma", "plasma", "coolwarm", "gray")

internal fun cmapGradient(name: String): List<String> = cmapStops(name)

private fun hexVal(s: String, at: Int): Int {
    var v = 0
    for (i in at until at + 2) {
        val c = s[i]
        v = v * 16 + (if (c >= 'a') c - 'a' + 10 else if (c >= 'A') c - 'A' + 10 else c - '0')
    }
    return v
}

private fun hex2(n: Int): String {
    val d = "0123456789abcdef"
    val v = if (n < 0) 0 else if (n > 255) 255 else n
    return d[v / 16].toString() + d[v % 16].toString()
}

/** RGB of the colour map [name] at [t] (0..1) as an `#rrggbb` string. */
internal fun cmapColor(name: String, t0: Double): String {
    val stops = cmapStops(name)
    val t = if (t0 != t0 || t0 < 0.0) 0.0 else if (t0 > 1.0) 1.0 else t0
    val x = t * (stops.size - 1).toDouble()
    var i = x.toInt()
    if (i > stops.size - 2) i = stops.size - 2
    val f = x - i.toDouble()
    val a = stops[i]
    val b = stops[i + 1]
    var out = "#"
    for (k in 0 until 3) {
        val p = hexVal(a, 1 + 2 * k).toDouble()
        val q = hexVal(b, 1 + 2 * k).toDouble()
        out += hex2((p + (q - p) * f + 0.5).toInt())
    }
    return out
}

/** Whether text on the colour [hex] should be dark (a light background). */
internal fun isLight(hex: String): Boolean {
    val r = hexVal(hex, 1).toDouble()
    val g = hexVal(hex, 3).toDouble()
    val b = hexVal(hex, 5).toDouble()
    return 0.299 * r + 0.587 * g + 0.114 * b > 150.0
}

/** Kept for compatibility: the default (viridis) colour of [t]. */
internal fun heatColor(t0: Double): String = cmapColor("viridis", t0)

internal fun dlist(d: DoubleArray): List<Double> {
    val out = ArrayList<Double>()
    for (v in d) out.add(v)
    return out
}

/** [v] with 3 significant digits, for the numbers inside heatmap cells. */
internal fun annotNum(v: Double): String {
    if (!isFin(v)) return shortNum(v)
    val a = abs(v)
    if (a >= 1000.0 || (a < 0.01 && a > 0.0)) return shortNum(v)
    if (a == 0.0) return "0"
    var decimals = 2 - floorLog10(a)
    if (decimals < 0) decimals = 0
    return trimZeros(fixed(v, decimals))
}
