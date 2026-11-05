// downTo / until / `..` between Byte, Short, Int and Long, and unsigned progressions with step.
fun main() {
    val b: Byte = 3
    val s: Short = 1
    val sb = StringBuilder()
    for (i in b downTo s) sb.append(i).append(' ')
    for (i in s..b) sb.append(i).append(' ')
    for (i in s until b) sb.append(i).append(' ')
    println(sb.toString().trim())
    val r = (1.toByte()..4.toByte())
    println(r.last)
    val l = StringBuilder()
    for (i in 5 downTo 3L) l.append(i).append(' ')
    for (i in 1L until 4) l.append(i).append(' ')
    println(l.toString().trim())
    println(2.toByte().downTo(0.toByte()).toList())
    println(5uL downTo 1uL step 2L)
    val u = StringBuilder()
    for (i in 1uL..9uL step 4L) u.append(i).append(' ')
    for (i in 10u downTo 1u step 3) u.append(i).append(' ')
    for (i in 1u.toUByte()..3u.toUByte()) u.append(i).append(' ')
    println(u.toString().trim())
    println((1u..<4u).toList())
    println((5uL downTo 1uL).reversed().toList())
}
