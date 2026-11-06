/*
 * Mini stdlib: Duration.parse / parseOrNull / parseIsoString / parseIsoStringOrNull. Accepted formats (as in kotlin-stdlib):
 * the ISO-8601 form `[-+]P[nD][T[nH][nM][n[.f]S]]` and the form that Duration.toString() writes: `[-+]` and then components
 * `n[.f]unit` (units d, h, m, s, ms, us, ns) separated by optional spaces; also `Infinity`.
 */
package kotlin.time

public fun Duration.Companion.parse(value: String): Duration =
    parseDurationOrNull(value, false) ?: throw IllegalArgumentException("Invalid duration string format: '" + value + "'.")

public fun Duration.Companion.parseOrNull(value: String): Duration? = parseDurationOrNull(value, false)

public fun Duration.Companion.parseIsoString(value: String): Duration =
    parseDurationOrNull(value, true) ?: throw IllegalArgumentException("Invalid ISO duration string format: '" + value + "'.")

public fun Duration.Companion.parseIsoStringOrNull(value: String): Duration? = parseDurationOrNull(value, true)

private fun unitNanos(unit: String): Long = when (unit) {
    "d" -> 86400000000000L
    "h" -> 3600000000000L
    "m" -> 60000000000L
    "s" -> 1000000000L
    "ms" -> 1000000L
    "us" -> 1000L
    "ns" -> 1L
    else -> -1L
}

/** One component: whole part (digits), optional fraction, the unit; returns the duration in nanoseconds or null. */
private fun component(whole: String, frac: String, nanosPerUnit: Long): Duration? {
    if (whole.isEmpty()) return null
    var w = 0L
    for (c in whole) {
        if (c < '0' || c > '9') return null
        w = w * 10L + (c.code - '0'.code).toLong()
    }
    var nanos = w.toDuration(DurationUnit.NANOSECONDS) * nanosPerUnit.toDouble()
    if (frac.isNotEmpty()) {
        var f = 0.0
        var scale = 0.1
        for (c in frac) {
            if (c < '0' || c > '9') return null
            f += (c.code - '0'.code).toDouble() * scale
            scale /= 10.0
        }
        nanos += (f * nanosPerUnit.toDouble()).toLong().toDuration(DurationUnit.NANOSECONDS)
    }
    return nanos
}

private fun parseDurationOrNull(value: String, isoOnly: Boolean): Duration? {
    val length = value.length
    if (length == 0) return null
    var i = 0
    var negative = false
    if (value[0] == '+' || value[0] == '-') {
        negative = value[0] == '-'
        i = 1
    }
    if (i == length) return null
    var result = Duration.ZERO
    if (value[i] == 'P') {
        i++
        if (i == length) return null
        var inTime = false
        var any = false
        while (i < length) {
            if (value[i] == 'T') {
                if (inTime) return null
                inTime = true
                i++
                if (i == length) return null
                continue
            }
            var start = i
            while (i < length && value[i] >= '0' && value[i] <= '9') i++
            val whole = value.substring(start, i)
            var frac = ""
            if (i < length && value[i] == '.') {
                i++
                start = i
                while (i < length && value[i] >= '0' && value[i] <= '9') i++
                frac = value.substring(start, i)
                if (frac.isEmpty()) return null
            }
            if (i == length) return null
            val u = value[i]
            i++
            val perUnit = if (!inTime && u == 'D') 86400000000000L
            else if (inTime && u == 'H') 3600000000000L
            else if (inTime && u == 'M') 60000000000L
            else if (inTime && u == 'S') 1000000000L
            else -1L
            if (perUnit < 0L) return null
            if (frac.isNotEmpty() && u != 'S') return null
            val d = component(whole, frac, perUnit) ?: return null
            result += d
            any = true
        }
        if (!any) return null
    } else {
        if (isoOnly) return null
        if (value.regionMatches(i, "Infinity", 0, 8) && length - i == 8) {
            return if (negative) -Duration.INFINITE else Duration.INFINITE
        }
        var any = false
        while (i < length) {
            if (any && value[i] == ' ') {
                i++
                if (i == length) return null
            }
            var start = i
            while (i < length && value[i] >= '0' && value[i] <= '9') i++
            val whole = value.substring(start, i)
            var frac = ""
            if (i < length && value[i] == '.') {
                i++
                start = i
                while (i < length && value[i] >= '0' && value[i] <= '9') i++
                frac = value.substring(start, i)
                if (frac.isEmpty()) return null
            }
            start = i
            while (i < length && value[i] >= 'a' && value[i] <= 'z') i++
            val perUnit = unitNanos(value.substring(start, i))
            if (perUnit < 0L) return null
            val d = component(whole, frac, perUnit) ?: return null
            result += d
            any = true
        }
        if (!any) return null
    }
    return if (negative) -result else result
}
