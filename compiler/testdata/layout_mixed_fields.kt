// Field reordering (docs/memory-model.md, "Object layout"): a class mixing Boolean, Byte, Short, Char, Int, Long, Double, Float and
// reference fields in an awkward declaration order. Every field must keep its own value (no overlap), through reads, writes,
// compound assignments and property accessors.
class Mixed(
    var flag: Boolean,
    var name: String,
    var count: Int,
    var big: Long,
    var tag: Byte,
    var ratio: Double,
    var letter: Char,
    var small: Short,
    var other: Mixed?,
    var second: Boolean,
    var f: Float,
) {
    var late: Int = 7
    var lateFlag: Boolean = true
    var lateLong: Long = 1L shl 40

    fun dump(): String =
        "$flag $name $count $big $tag $ratio $letter $small ${other?.name} $second $f $late $lateFlag $lateLong"
}

fun main() {
    val a = Mixed(true, "a", 1, 10_000_000_000L, 5, 2.5, 'x', 300, null, false, 1.5f)
    val b = Mixed(false, "b", -2, -3L, -1, -0.125, 'y', -7, a, true, -2.25f)
    println(a.dump())
    println(b.dump())
    a.flag = false
    a.second = true
    a.tag = (a.tag + 100).toByte()
    a.small = (a.small * 2).toShort()
    a.count += 41
    a.big *= 3
    a.letter = 'z'
    a.late = 99
    a.lateFlag = false
    a.lateLong = -1L
    a.f = a.f * 4
    a.ratio = a.ratio / 2
    a.other = b
    a.name = a.name + "!"
    println(a.dump())
    println(b.dump())
    val all = Array(5) { i -> Mixed(i % 2 == 0, "n$i", i, i.toLong() * 1_000_000_007L, i.toByte(), i / 4.0, 'a' + i, i.toShort(), null, i % 3 == 0, i.toFloat()) }
    for (m in all) println(m.dump())
    var sum = 0L
    for (m in all) sum += m.count + m.big + m.tag + m.small + (if (m.flag) 1 else 0) + (if (m.second) 10 else 0) + m.late
    println(sum)
}
