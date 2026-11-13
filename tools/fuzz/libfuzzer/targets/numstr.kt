// libFuzzer target: number -> String (Int/Long/UInt/ULong with a radix, Double, Float, hex) and the round trip.
// The numbers are made of the characters of the input (16 bits of each of the first four characters).

fun fuzzNumStr(s: String, a: Int, b: Int): Int {
    var bits = 0L
    for (i in 0 until 4) bits = (bits shl 16) or (if (i < s.length) s[i].code.toLong() else 0L)
    var h = 0
    val radix = 2 + a % 35
    val l = bits
    val i = bits.toInt()
    check(l.toString(radix).toLong(radix) == l)
    check(i.toString(radix).toInt(radix) == i)
    check(l.toString().toLong() == l)
    check(i.toString().toInt() == i)
    check(l.toULong().toString(radix).toULong(radix) == l.toULong())
    check(i.toUInt().toString(radix).toUInt(radix) == i.toUInt())
    check(l.toHexString().hexToLong() == l)
    check(i.toHexString().hexToInt() == i)
    check(i.toShort().toString(radix).toShort(radix) == i.toShort())
    check(i.toByte().toString(radix).toByte(radix) == i.toByte())
    val d = Double.fromBits(bits)
    val ds = d.toString()
    if (!d.isNaN()) check(ds.toDouble().toRawBits() == d.toRawBits()) else check(ds == "NaN")
    val f = Float.fromBits(i)
    val fs = f.toString()
    if (!f.isNaN()) check(fs.toFloat().toRawBits() == f.toRawBits()) else check(fs == "NaN")
    h += ds.length + fs.length + l.toString(radix).length
    val w = b % 40
    check(i.toString().padStart(w, '0').length >= w)
    h += (d.toInt() + f.toLong().toInt())
    h += "$d$f$l$i".length
    return h
}

fun main() {
    println(fuzzNumStr("abcd", 8, 0))
}
