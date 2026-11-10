import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or
import kotlin.experimental.xor

fun main() {
    println((-1).toByte().toString(16))
    println(255.toShort().toString(2))
    println(200u.toUByte().toString(16))
    println(65535u.toUShort().toString(36))
    println(4000000000u.toString(16))
    println(ULong.MAX_VALUE.toString(16))
    println(ULong.MAX_VALUE.toString(10))
    println(ULong.MAX_VALUE.toString(2))
    println(12345678901234567890uL.toString(7))
    println(0xF0.toByte() and 0x3C.toByte())
    println(0x0F.toByte() or 0x30.toByte())
    println(0xFF.toByte() xor 0x0F.toByte())
    println(5.toByte().inv())
    println((-300).toShort() and 0xFF.toShort())
    println(1000.toShort().inv())
    println(((-2).toShort() or 1.toShort()))
    println(0x10.toByte().takeHighestOneBit())
    println(0x6C.toByte().takeLowestOneBit())
    println(0x81.toByte().rotateLeft(1))
    println(0x81.toByte().rotateRight(1))
    println(0x1234.toShort().rotateLeft(4))
    println(0x1234.toShort().rotateRight(4))
    println((-7).toByte().floorDiv(2.toByte()))
    println((-7).toByte().mod(3.toByte()))
    println((-7).toShort().floorDiv(2))
    println((-7).toShort().mod(3))
    println(7.toByte().mod(-3L))
    println(0x1234.toShort().takeHighestOneBit())
    println(0x1230.toShort().takeLowestOneBit())
}
