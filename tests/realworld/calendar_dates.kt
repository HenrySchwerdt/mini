// Calendar arithmetic without any date library: days since epoch, weekdays, leap years, month calendars, date differences,
// adding months, ISO week numbers, parsing and formatting, and an event reminder scan.

enum class Weekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

class Date(val year: Int, val month: Int, val day: Int) : Comparable<Date> {
    init {
        require(month in 1..12) { "bad month $month" }
        require(day in 1..daysInMonth(year, month)) { "bad day $day for $year-$month" }
    }

    // days since 1970-01-01 (algorithm of Howard Hinnant)
    fun epochDay(): Long {
        val y = if (month <= 2) year - 1L else year.toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    val weekday: Weekday get() = Weekday.values()[((epochDay() + 3).mod(7L)).toInt()]
    val dayOfYear: Int get() = (1 until month).sumOf { daysInMonth(year, it) } + day

    fun plusDays(n: Long): Date = fromEpochDay(epochDay() + n)

    fun plusMonths(n: Int): Date {
        val total = year * 12 + (month - 1) + n
        val y = Math.floorDiv(total, 12)
        val m = total - y * 12 + 1
        return Date(y, m, minOf(day, daysInMonth(y, m)))
    }

    fun isoWeek(): Int {
        val thursday = plusDays(3L - weekday.ordinal)
        return (thursday.dayOfYear - 1) / 7 + 1
    }

    override fun compareTo(other: Date) = epochDay().compareTo(other.epochDay())
    override fun equals(other: Any?) = other is Date && epochDay() == other.epochDay()
    override fun hashCode() = epochDay().hashCode()
    override fun toString() = "%04d-%02d-%02d".format(year, month, day)

    companion object {
        fun fromEpochDay(z0: Long): Date {
            val z = z0 + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            return Date((if (m <= 2) y + 1 else y).toInt(), m, d)
        }

        fun parse(s: String): Date {
            val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})").matchEntire(s.trim()) ?: throw IllegalArgumentException("not a date: '$s'")
            val (y, mo, d) = m.destructured
            return Date(y.toInt(), mo.toInt(), d.toInt())
        }
    }
}

object Math {
    fun floorDiv(a: Int, b: Int): Int = if (a % b != 0 && (a < 0) != (b < 0)) a / b - 1 else a / b
}

fun isLeap(y: Int) = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
fun daysInMonth(y: Int, m: Int) = when (m) {
    2 -> if (isLeap(y)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

fun monthCalendar(year: Int, month: Int): String {
    val names = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val title = "${names[month - 1]} $year"
    val sb = StringBuilder(title.padStart((20 + title.length) / 2).trimEnd()).append('\n').append("Mo Tu We Th Fr Sa Su\n")
    val first = Date(year, month, 1).weekday.ordinal
    sb.append("   ".repeat(first))
    for (d in 1..daysInMonth(year, month)) {
        sb.append(d.toString().padStart(2))
        sb.append(if ((first + d) % 7 == 0) '\n' else ' ')
    }
    return sb.toString().trimEnd()
}

fun main() {
    println(Date(1970, 1, 1).epochDay().toString() + " " + Date(2000, 3, 1).epochDay() + " " + Date(1969, 12, 31).epochDay() + " " + Date(2024, 2, 29).epochDay())
    println(listOf("1970-01-01", "2000-02-29", "2024-12-25", "1999-12-31", "1900-01-01").map { Date.parse(it).weekday.name.take(3) })
    println(listOf(1900, 2000, 2023, 2024, 2100).map { isLeap(it) })
    println(monthCalendar(2024, 2))
    println(monthCalendar(2023, 10))
    val a = Date.parse("2024-03-15")
    println("${a.plusDays(30)} ${a.plusDays(-100)} ${a.plusMonths(11)} ${Date.parse("2024-01-31").plusMonths(1)} ${a.plusMonths(-15)}")
    val b = Date.parse("2025-01-01")
    println("days between: ${b.epochDay() - a.epochDay()}, day of year: ${a.dayOfYear}, ${Date(2024, 12, 31).dayOfYear}")
    println("iso weeks: " + listOf("2021-01-01", "2021-01-04", "2020-12-31", "2024-12-30", "2026-01-01").map { Date.parse(it).isoWeek() })
    println(Date.fromEpochDay(0).toString() + " " + Date.fromEpochDay(19800) + " " + Date.fromEpochDay(-1) + " " + Date.fromEpochDay(-719468))
    println(sortedSet(listOf("2024-05-01", "2023-12-31", "2024-01-15")))
    for (bad in listOf("2024-13-01", "2023-02-29", "hello", "2024-4-31")) {
        try {
            println(Date.parse(bad))
        } catch (e: IllegalArgumentException) {
            println("error: ${e.message}")
        }
    }
    val events = mapOf("dentist" to "2024-03-18", "birthday" to "2024-03-20", "holiday" to "2024-04-30", "past" to "2024-03-01")
    val today = a
    for ((name, d) in events.entries.sortedBy { Date.parse(it.value) }) {
        val diff = Date.parse(d).epochDay() - today.epochDay()
        println("$name on $d (${Date.parse(d).weekday}): " + if (diff < 0) "${-diff} days ago" else if (diff == 0L) "today" else "in $diff days")
    }
    var fridays13 = 0
    for (y in 2020..2030) for (m in 1..12) if (Date(y, m, 13).weekday == Weekday.FRIDAY) fridays13++
    println("Friday the 13th between 2020 and 2030: $fridays13")
}

fun sortedSet(xs: List<String>) = xs.map { Date.parse(it) }.sorted().joinToString(" < ")
