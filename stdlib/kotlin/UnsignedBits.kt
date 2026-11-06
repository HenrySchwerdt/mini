/*
 * Mini stdlib: bit counting and rotation for the unsigned types (kotlin-stdlib UNumbers.kt) and Char.toChar().
 */
package kotlin

public fun UInt.countOneBits(): Int = data.countOneBits()
public fun UInt.countLeadingZeroBits(): Int = data.countLeadingZeroBits()
public fun UInt.countTrailingZeroBits(): Int = data.countTrailingZeroBits()
public fun UInt.takeHighestOneBit(): UInt = UInt(data.takeHighestOneBit())
public fun UInt.takeLowestOneBit(): UInt = UInt(data.takeLowestOneBit())
public fun UInt.rotateLeft(bitCount: Int): UInt = UInt(data.rotateLeft(bitCount))
public fun UInt.rotateRight(bitCount: Int): UInt = UInt(data.rotateRight(bitCount))

public fun ULong.countOneBits(): Int = data.countOneBits()
public fun ULong.countLeadingZeroBits(): Int = data.countLeadingZeroBits()
public fun ULong.countTrailingZeroBits(): Int = data.countTrailingZeroBits()
public fun ULong.takeHighestOneBit(): ULong = ULong(data.takeHighestOneBit())
public fun ULong.takeLowestOneBit(): ULong = ULong(data.takeLowestOneBit())
public fun ULong.rotateLeft(bitCount: Int): ULong = ULong(data.rotateLeft(bitCount))
public fun ULong.rotateRight(bitCount: Int): ULong = ULong(data.rotateRight(bitCount))

public fun UByte.countOneBits(): Int = (data.toInt() and 0xFF).countOneBits()
public fun UByte.countLeadingZeroBits(): Int = (data.toInt() and 0xFF).countLeadingZeroBits() - 24
public fun UByte.countTrailingZeroBits(): Int = (data.toInt() or 0x100).countTrailingZeroBits()
public fun UByte.takeHighestOneBit(): UByte = UByte((data.toInt() and 0xFF).takeHighestOneBit().toByte())
public fun UByte.takeLowestOneBit(): UByte = UByte((data.toInt() and 0xFF).takeLowestOneBit().toByte())
public fun UByte.rotateLeft(bitCount: Int): UByte {
    val n = bitCount and 7
    val v = data.toInt() and 0xFF
    return UByte(((v shl n) or (v ushr (8 - n))).toByte())
}
public fun UByte.rotateRight(bitCount: Int): UByte {
    val n = bitCount and 7
    val v = data.toInt() and 0xFF
    return UByte(((v ushr n) or (v shl (8 - n))).toByte())
}

public fun UShort.countOneBits(): Int = (data.toInt() and 0xFFFF).countOneBits()
public fun UShort.countLeadingZeroBits(): Int = (data.toInt() and 0xFFFF).countLeadingZeroBits() - 16
public fun UShort.countTrailingZeroBits(): Int = (data.toInt() or 0x10000).countTrailingZeroBits()
public fun UShort.takeHighestOneBit(): UShort = UShort((data.toInt() and 0xFFFF).takeHighestOneBit().toShort())
public fun UShort.takeLowestOneBit(): UShort = UShort((data.toInt() and 0xFFFF).takeLowestOneBit().toShort())
public fun UShort.rotateLeft(bitCount: Int): UShort {
    val n = bitCount and 15
    val v = data.toInt() and 0xFFFF
    return UShort(((v shl n) or (v ushr (16 - n))).toShort())
}
public fun UShort.rotateRight(bitCount: Int): UShort {
    val n = bitCount and 15
    val v = data.toInt() and 0xFFFF
    return UShort(((v ushr n) or (v shl (16 - n))).toShort())
}

public fun Byte.countOneBits(): Int = (toInt() and 0xFF).countOneBits()
public fun Byte.countLeadingZeroBits(): Int = (toInt() and 0xFF).countLeadingZeroBits() - 24
public fun Byte.countTrailingZeroBits(): Int = (toInt() or 0x100).countTrailingZeroBits()
public fun Short.countOneBits(): Int = (toInt() and 0xFFFF).countOneBits()
public fun Short.countLeadingZeroBits(): Int = (toInt() and 0xFFFF).countLeadingZeroBits() - 16
public fun Short.countTrailingZeroBits(): Int = (toInt() or 0x10000).countTrailingZeroBits()

public fun Char.toChar(): Char = this
