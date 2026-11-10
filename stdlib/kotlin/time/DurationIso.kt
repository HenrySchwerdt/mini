/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Duration.toIsoString (the inverse of Duration.parseIsoString), after kotlin-stdlib kotlin/time/Duration.kt.
 */
package kotlin.time

/**
 * Returns an ISO-8601 based string representation of this duration: `PT1H30M`, `PT0.5S`, `-PT5S`.
 * Infinite durations are represented as `PT2562047788015215H30M7.999999999S`-like maximal values (`PT9999999999999H`).
 */
public fun Duration.toIsoString(): String = buildString {
    if (isNegative()) append('-')
    append("PT")
    this@toIsoString.absoluteValue.toComponents { hours, minutes, seconds, nanoseconds ->
        var h = hours
        if (this@toIsoString.isInfinite()) {
            // use large enough value instead of Long.MAX_VALUE
            h = 9_999_999_999_999
        }
        val hasHours = h != 0L
        val hasSeconds = seconds != 0 || nanoseconds != 0
        val hasMinutes = minutes != 0 || (hasSeconds && hasHours)
        if (hasHours) {
            append(h).append('H')
        }
        if (hasMinutes) {
            append(minutes).append('M')
        }
        if (hasSeconds || (!hasHours && !hasMinutes)) {
            append(seconds)
            if (nanoseconds != 0) {
                append('.')
                val fracString = nanoseconds.toString().padStart(9, '0')
                val nonZeroDigits = fracString.indexOfLast { it != '0' } + 1
                append(fracString.substring(0, ((nonZeroDigits + 2) / 3) * 3))
            }
            append('S')
        }
    }
}
