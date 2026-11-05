import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.toDuration

fun main() {
    println(1.5.seconds)
    println(90.minutes)
    println(1234.milliseconds)
    println(0.seconds)
    println(500.microseconds)
    println(45.nanoseconds)
    println(2.days + 3.hours + 4.minutes + 5.seconds)
    println((-90).minutes)
    println(Duration.INFINITE)
    println((3.seconds * 2).inWholeMilliseconds)
    println(1.hours / 4)
    println(1.5.seconds.toString(DurationUnit.MILLISECONDS, 2))
    println(5.seconds > 3.seconds)
    println(3.seconds / 2.seconds)
    println(measureTime { var x = 0; for (i in 0..1000) x += i } >= Duration.ZERO)
    println(42.toDuration(DurationUnit.SECONDS))
    val a = Random(42)
    val b = Random(42)
    println(a.nextInt() == b.nextInt() && a.nextLong() == b.nextLong() && a.nextDouble() == b.nextDouble())
    val r = Random(1)
    var ok = true
    for (i in 0 until 200) {
        val v = r.nextInt(5, 10)
        if (v < 5 || v >= 10) ok = false
        val x = r.nextDouble()
        if (x < 0.0 || x >= 1.0) ok = false
    }
    println(ok)
    println(listOf(1, 2, 3, 4, 5).shuffled(Random(3)).sorted())
    println(2.5.roundToInt())
    println((-2.5).roundToInt())
    println(7.mod(3))
    println((-7).mod(3))
    println((-7).floorDiv(2))
    println(5.countOneBits())
}
