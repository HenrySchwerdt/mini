// integer overflow, conversions, shifts, bit ops, division and remainder semantics
fun main() {
    val mx = Int.MAX_VALUE; val mn = Int.MIN_VALUE
    println(mx + 1); println(mn - 1); println(mx * 2); println(-mn); println(mn / -1); println(mn % -1)
    println(7 / 2); println(-7 / 2); println(7 / -2); println(7 % 3); println(-7 % 3); println(7 % -3); println(7.0 % 3); println(-7.5 % 2); println(7.floorDiv(-2)); println((-7).mod(3)); println((-7).rem(3)); println(7.5.rem(2))
    println(1 shl 31); println(1 shl 32); println(1 shl 33); println(-1 ushr 28); println(-16 shr 2); println(-16 ushr 28); println(1L shl 63); println(1L shl 64); println(5 and 3); println(5 or 3); println(5 xor 3); println(5.inv()); println(0xFF and 0x0F); println(0b1010 or 1)
    println(300.toByte()); println(200.toByte()); println((-1).toByte().toInt()); println(255.toByte().toUByte()); println(70000.toShort()); println(3.99.toInt()); println(-3.99.toInt()); println(1e20.toInt()); println(-1e20.toInt()); println(Double.NaN.toInt()); println(1e20.toLong()); println(3000000000L.toInt())
    println(Long.MAX_VALUE + 1); println(Long.MIN_VALUE - 1); println(Long.MAX_VALUE.toInt()); println(Long.MAX_VALUE.toDouble()); println(Int.MAX_VALUE.toFloat()); println(16777217.toFloat()); println(0.1f + 0.2f); println(0.1 + 0.2)
    println(1 / 2.0); println(1.0 / 0); println(-1.0 / 0); println(0.0 / 0.0); println(10 / 4 * 4); println(10 / 4.0 * 4); println(1_000_000 * 3); println(1_000_000L * 3_000_000)
    println('a'.code + 1); println(1.toChar()); println((65 + 1).toChar()); println(Byte.MAX_VALUE + 1); println((Byte.MAX_VALUE + 1).toByte()); println(Short.MIN_VALUE - 1); println(UInt.MAX_VALUE); println(UInt.MAX_VALUE + 1u); println(0u - 1u); println(5u / 2u); println(ULong.MAX_VALUE); println(10uL % 3uL)
    println(2.5.toInt()); println(2.5.toLong()); println(Int.MAX_VALUE.toLong() + 1); println(1e3.toInt()); println(255.toString(16)); println((-255).toString(2)); println("ff".toInt(16)); println("-80000000".toLong(16))
    println(Int.MIN_VALUE.toString()); println(Long.MIN_VALUE); println(Int.MAX_VALUE.inc()); println(Int.MIN_VALUE.dec()); println(5.coerceIn(1, 3)); println((-5).coerceAtLeast(0)); println(5.coerceAtMost(3)); println(Int.MAX_VALUE.countOneBits()); println(8.countTrailingZeroBits()); println(1.countLeadingZeroBits()); println(0x12345678.rotateLeft(8).toString(16)); println(6.takeHighestOneBit()); println(6.takeLowestOneBit())
}
