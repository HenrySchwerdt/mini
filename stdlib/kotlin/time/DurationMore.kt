/*
 * Mini stdlib: more of kotlin.time.Duration (toComponents with fewer components, toIsoString, sums).
 */
package kotlin.time

public inline fun <T> Duration.toComponents(action: (hours: Long, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T =
    action(inWholeHours, minutesComponent, secondsComponent, nanosecondsComponent)

public inline fun <T> Duration.toComponents(action: (minutes: Long, seconds: Int, nanoseconds: Int) -> T): T =
    action(inWholeMinutes, secondsComponent, nanosecondsComponent)

public inline fun <T> Duration.toComponents(action: (seconds: Long, nanoseconds: Int) -> T): T =
    action(inWholeSeconds, nanosecondsComponent)

public fun Duration.toIsoString(): String {
    val sb = StringBuilder()
    if (isNegative()) sb.append('-')
    sb.append("PT")
    val abs = absoluteValue
    var hours = abs.inWholeHours
    val minutes = abs.minutesComponent
    val seconds = abs.secondsComponent
    val nanoseconds = abs.nanosecondsComponent
    if (isInfinite()) hours = 9999999999999L
    val hasHours = hours != 0L
    val hasSeconds = seconds != 0 || nanoseconds != 0
    val hasMinutes = minutes != 0 || (hasSeconds && hasHours)
    if (hasHours) {
        sb.append(hours).append('H')
    }
    if (hasMinutes) {
        sb.append(minutes).append('M')
    }
    if (hasSeconds || (!hasHours && !hasMinutes)) {
        sb.append(seconds)
        if (nanoseconds != 0) {
            sb.append('.')
            val frac = nanoseconds.toString().padStart(9, '0')
            var nonZero = frac.length
            while (nonZero > 0 && frac[nonZero - 1] == '0') nonZero--
            sb.append(frac.substring(0, ((nonZero + 2) / 3) * 3))
        }
        sb.append('S')
    }
    return sb.toString()
}

public fun Iterable<Duration>.sum(): Duration {
    var sum = Duration.ZERO
    for (element in this) sum += element
    return sum
}

public fun Sequence<Duration>.sum(): Duration {
    var sum = Duration.ZERO
    for (element in this) sum += element
    return sum
}
