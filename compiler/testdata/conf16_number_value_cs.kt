class Num(val v: Int) : Number() {
    override fun toInt() = v
    override fun toLong() = v.toLong()
    override fun toDouble() = v.toDouble()
    override fun toFloat() = v.toFloat()
    override fun toShort() = v.toShort()
    override fun toByte() = v.toByte()
}
@JvmInline value class Wrap<T>(val x: T)
@JvmInline value class UInt2(val v: Int)
fun cs(c: CharSequence): String { val sb = StringBuilder(); for (ch in c) sb.append(ch.uppercaseChar()); for (i in c.indices) sb.append(i); for ((i, ch) in c.withIndex()) sb.append(ch).append(i); return sb.toString() }
fun main() {
    val n: Number = Num(7)
    println(n.toInt()); println(n.toDouble()); println(n.toChar().code)
    println(Wrap("a").x + Wrap(3).x)
    println(UInt2(3))
    println(cs("ab"))
    println(cs(StringBuilder("xy")))
    val l = buildList { add(1); add(2) }
    val m = buildMap { put("a", 1) }
    val s = sequence { yield(1); yield(2) }.toList()
    println("$l $m $s")
}
