import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTime::class)
fun main() {
    val ts = TestTimeSource()
    val start = ts.markNow()
    println(start.elapsedNow())
    ts += 1500.milliseconds
    println(start.elapsedNow())
    val later = ts.markNow()
    ts += 2.seconds
    println(later.elapsedNow())
    println(later - start)
    println(start < later)
    println(start == start + 0.seconds)
    println((later + 10.seconds).hasPassedNow())
    println((later - 10.seconds).hasPassedNow())
    println((later + 10.seconds).hasNotPassedNow())
    println(start.elapsedNow() > later.elapsedNow())
    val shifted = start + 1.seconds
    println(shifted.elapsedNow())
    println(ts.measureTime { ts += 3.seconds })
    val tv = ts.measureTimedValue { ts += 250.milliseconds; "x" }
    println(tv.value + " " + tv.duration)
    println(tv)
    val m = TimeSource.Monotonic.markNow()
    println(m.elapsedNow() < 10.seconds)
    println(m.hasPassedNow())
    val d = measureTime { var s = 0; for (i in 0 until 1000) s += i }
    println(d.isNegative())
    val (v, dur) = measureTimedValue { 6 * 7 }
    println(v.toString() + " " + (dur >= Duration.ZERO))
    val m2 = TimeSource.Monotonic.markNow()
    println((m2 - m) >= Duration.ZERO)
    println(m2 > m)
    val far = m + Duration.INFINITE
    println(far.hasNotPassedNow())
    try {
        ts += Duration.INFINITE
    } catch (e: IllegalStateException) {
        println("ISE")
    }
    val other = TestTimeSource()
    try {
        println(other.markNow() - start)
    } catch (e: IllegalArgumentException) {
        println("IAE")
    }
    println(ts.markNow() is ComparableTimeMark)
}
