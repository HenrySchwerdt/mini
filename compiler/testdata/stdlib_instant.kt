import kotlin.time.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTime::class)
fun testInstant() {
    println("== Instant")
    val a = Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
    println(a)
    println(a.epochSeconds)
    println(a.nanosecondsOfSecond)
    println(a.toEpochMilliseconds())
    println(Instant.fromEpochMilliseconds(0))
    println(Instant.fromEpochMilliseconds(-1))
    println(Instant.fromEpochMilliseconds(1_700_000_000_123))
    println(Instant.fromEpochSeconds(86_400))
    println(Instant.fromEpochSeconds(1, 1_500_000_000L))
    println(Instant.fromEpochSeconds(1, -1L))
    val b = a + 1.days + 2.hours + 3.minutes + 4.seconds + 5.milliseconds
    println(b)
    println(b - a)
    println(a - b)
    println(a - 1.hours)
    println(a + Duration.ZERO == a)
    println(a < b)
    println(a > b)
    println(a.compareTo(a))
    println(a == Instant.fromEpochSeconds(1_700_000_000, 123_456_789))
    println(a.hashCode() == Instant.fromEpochSeconds(1_700_000_000, 123_456_789).hashCode())
    println(Instant.DISTANT_PAST)
    println(Instant.DISTANT_FUTURE)
    println(Instant.DISTANT_PAST.isDistantPast)
    println(Instant.DISTANT_FUTURE.isDistantFuture)
    println(a.isDistantPast)
    println(a.isDistantFuture)
    println(Instant.parse("2023-11-14T22:13:20Z"))
    println(Instant.parse("2023-11-14T22:13:20.5Z").nanosecondsOfSecond)
    println(Instant.parse("2023-11-14T22:13:20.123456789Z").toEpochMilliseconds())
    println(Instant.parse("2023-11-14T22:13:20+01:00"))
    println(Instant.parse("2024-02-29T00:00:00Z").epochSeconds)
    println(Instant.parse("1969-12-31T23:59:59Z").epochSeconds)
    println(Instant.parse("+12345-01-01T00:00:00Z"))
    println(Instant.parse("-0001-06-15T12:00:00Z"))
    try { Instant.parse("2023-13-14T22:13:20Z") } catch (e: IllegalArgumentException) { println("bad month") }
    try { Instant.parse("garbage") } catch (e: IllegalArgumentException) { println("garbage") }
    println(a.toString() == Instant.parse(a.toString()).toString())
    println(Instant.fromEpochSeconds(Long.MAX_VALUE) == Instant.fromEpochSeconds(Long.MAX_VALUE))
}

@OptIn(ExperimentalTime::class)
fun testClock() {
    println("== Clock")
    val c = Clock.System
    val t1 = c.now()
    val t2 = Clock.System.now()
    println(t2 >= t1)
    println(t1.epochSeconds > 1_600_000_000)
    println((t2 - t1) < 1.minutes)
    val fixed = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(42)
    }
    println(fixed.now())
}

fun testIso() {
    println("== Duration ISO")
    println(Duration.ZERO.toIsoString())
    println(1.hours.toIsoString())
    println((90.minutes).toIsoString())
    println((1.hours + 30.minutes + 15.seconds).toIsoString())
    println((-5).seconds.toIsoString())
    println(500.milliseconds.toIsoString())
    println(1500.milliseconds.toIsoString())
    println(1234567.nanoseconds.toIsoString())
    println((1.hours + 5.seconds).toIsoString())
    println(2.days.toIsoString())
    println(Duration.INFINITE.toIsoString())
    println((-Duration.INFINITE).toIsoString())
    println(Duration.parseIsoString("PT1H30M").toIsoString())
    println(Duration.parseIsoString("PT0.5S"))
    println(Duration.parseIsoString("-PT5S"))
    println(Duration.parseIsoStringOrNull("nope"))
}

fun main() {
    testInstant()
    testClock()
    testIso()
}
