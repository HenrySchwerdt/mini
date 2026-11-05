fun id(x: Byte): Byte = x

fun <T> show(x: T): String = "<" + x.toString() + ">"

fun main() {
    val b: Byte = 100
    val s: Short = 30000
    println(b + b)
    println((b + b).toByte())
    println(s * 2)
    println((s * 2).toShort())
    println(b.toInt() and 0xF0)
    println((-1).toByte())
    println(200.toByte())
    println(70000.toShort())
    println(b / 3)
    println(b % 7)
    println(-b)
    var c: Byte = 127
    c++
    println(c)
    var d: Short = -32768
    d--
    println(d)
    val bn: Byte? = b
    val sn: Short? = s
    println(bn)
    println(sn)
    val nn: Byte? = null
    println(nn)
    println(bn == 100.toByte())
    println(bn!! + 1)
    val l = listOf<Byte>(1, 2, 3)
    println(l)
    val ls = listOf<Short>(10, 20, -30)
    println(ls)
    val a: Any = b
    println(a)
    println(a is Byte)
    println(a is Short)
    println(a is Int)
    val a2: Any = s
    println(a2 is Short)
    println(a2 == s)
    println(a2 == 30000)
    println(show(b))
    println(show(s))
    println(b.hashCode())
    println(s.hashCode())
    println(b.compareTo(50.toByte()))
    println(b.toLong() + 1L)
    println(b.toDouble() / 3)
    println(b.toFloat())
    println(b.toChar())
    println(Byte.MAX_VALUE)
    println(Short.MIN_VALUE)
    println("b=$b s=$s")
    val m = HashMap<Byte, Short>()
    m[1.toByte()] = 2.toShort()
    m[3.toByte()] = 4.toShort()
    println(m.size)
    println(m[1.toByte()])
    val ba = ByteArray(4)
    ba[0] = 1
    ba[1] = -2
    ba[2] = 127
    ba[3] = (ba[2] + 1).toByte()
    println(ba.contentToString())
    println(ba.size)
    var sum = 0
    for (x in ba) sum += x
    println(sum)
    for (i in ba.indices) ba[i] = (ba[i] * 2).toByte()
    println(ba.contentToString())
    val sa = shortArrayOf(1, 2, 3, -4)
    println(sa.contentToString())
    println(sa[3])
    val ba2 = byteArrayOf(5, 6, 7)
    println(ba2.contentToString())
    val ia = ByteArray(3) { (it * 3).toByte() }
    println(ia.contentToString())
    val sh = ShortArray(2) { (it + 1000).toShort() }
    println(sh.contentToString())
    println(id(5))
    val bytes: List<Byte?> = listOf<Byte?>(1, null, 3)
    println(bytes)
    println(b in 1..127)
}
