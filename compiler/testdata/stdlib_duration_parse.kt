import kotlin.time.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    println(Duration.parse("1h 30m"))
    println(Duration.parse("1h30m"))
    println(Duration.parse("PT1H30M"))
    println(Duration.parse("-PT1H30M"))
    println(Duration.parse("2d 3h"))
    println(Duration.parse("1.5s"))
    println(Duration.parse("500ms"))
    println(Duration.parse("-1m 30s"))
    println(Duration.parse("Infinity"))
    println(Duration.parse("PT0.25S"))
    println(Duration.parseOrNull("abc"))
    println(Duration.parseOrNull(""))
    println(Duration.parseOrNull("1x"))
    println(Duration.parse("1h") == 60.minutes)
    println(Duration.parseIsoString("P1DT2H"))
    println(Duration.parseIsoStringOrNull("1h"))
    println(Duration.parse(90.seconds.toString()))
    println(Duration.parse((1.hours + 2.minutes + 3.seconds + 4.milliseconds).toIsoString()))
    try {
        Duration.parse("nope")
    } catch (e: IllegalArgumentException) {
        println(e.message)
    }
}
