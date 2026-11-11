/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.uuid.Uuid (ported from kotlin-stdlib commonMain).
 */

package kotlin.uuid

import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
public class Uuid private constructor(
    @PublishedApi internal val mostSignificantBits: Long,
    @PublishedApi internal val leastSignificantBits: Long
) : Comparable<Uuid> {
    public inline fun <T> toLongs(action: (mostSignificantBits: Long, leastSignificantBits: Long) -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        return action(mostSignificantBits, leastSignificantBits)
    }
    public inline fun <T> toULongs(action: (mostSignificantBits: ULong, leastSignificantBits: ULong) -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }
        return action(mostSignificantBits.toULong(), leastSignificantBits.toULong())
    }

    override fun toString(): String {
        return toHexDashString()
    }
    public fun toHexDashString(): String {
        val bytes = ByteArray(36)
        mostSignificantBits.formatBytesInto(bytes, 0, startIndex = 0, endIndex = 4)
        bytes[8] = '-'.code.toByte()
        mostSignificantBits.formatBytesInto(bytes, 9, startIndex = 4, endIndex = 6)
        bytes[13] = '-'.code.toByte()
        mostSignificantBits.formatBytesInto(bytes, 14, startIndex = 6, endIndex = 8)
        bytes[18] = '-'.code.toByte()
        leastSignificantBits.formatBytesInto(bytes, 19, startIndex = 0, endIndex = 2)
        bytes[23] = '-'.code.toByte()
        leastSignificantBits.formatBytesInto(bytes, 24, startIndex = 2, endIndex = 8)
        return bytes.decodeToString()
    }

    public fun toHexString(): String {
        val bytes = ByteArray(32)
        mostSignificantBits.formatBytesInto(bytes, 0, startIndex = 0, endIndex = 8)
        leastSignificantBits.formatBytesInto(bytes, 16, startIndex = 0, endIndex = 8)
        return bytes.decodeToString()
    }

    public fun toByteArray(): ByteArray {
        val bytes = ByteArray(SIZE_BYTES)
        bytes.setLongAt(0, mostSignificantBits)
        bytes.setLongAt(8, leastSignificantBits)
        return bytes
    }
    public fun toUByteArray(): UByteArray {
        return UByteArray(storage = toByteArray())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Uuid) return false
        return mostSignificantBits == other.mostSignificantBits &&
                leastSignificantBits == other.leastSignificantBits
    }
    override fun compareTo(other: Uuid): Int {
        return if (mostSignificantBits != other.mostSignificantBits)
            mostSignificantBits.toULong().compareTo(other.mostSignificantBits.toULong())
        else
            leastSignificantBits.toULong().compareTo(other.leastSignificantBits.toULong())
    }

    override fun hashCode(): Int {
        return (mostSignificantBits xor leastSignificantBits).hashCode()
    }


    public companion object {

        public val NIL: Uuid = Uuid(0, 0)

        public const val SIZE_BYTES: Int = 16

        public const val SIZE_BITS: Int = 128

        public fun fromLongs(mostSignificantBits: Long, leastSignificantBits: Long): Uuid =
            if (mostSignificantBits == 0L && leastSignificantBits == 0L) {
                NIL
            } else {
                Uuid(mostSignificantBits, leastSignificantBits)
            }

        public fun fromULongs(mostSignificantBits: ULong, leastSignificantBits: ULong): Uuid =
            fromLongs(mostSignificantBits.toLong(), leastSignificantBits.toLong())

        public fun fromByteArray(byteArray: ByteArray): Uuid {
            require(byteArray.size == SIZE_BYTES) {
                "Expected exactly $SIZE_BYTES bytes, but was ${byteArray.truncateForErrorMessage(32)} of size ${byteArray.size}"
            }

            return fromLongs(byteArray.getLongAt(index = 0), byteArray.getLongAt(index = 8))
        }
        public fun fromUByteArray(ubyteArray: UByteArray): Uuid {
            return fromByteArray(ubyteArray.storage)
        }

        public fun parse(uuidString: String): Uuid {
            return when (uuidString.length) {
                36 -> uuidParseHexDash(uuidString)
                32 -> uuidParseHex(uuidString)
                else -> throw IllegalArgumentException(
                    "Expected either a 36-char string in the standard hex-and-dash UUID format or a 32-char hexadecimal string, " +
                            "but was \"${uuidString.truncateForErrorMessage(64)}\" of length ${uuidString.length}"
                )
            }
        }
        public fun parseHexDash(hexDashString: String): Uuid {
            require(hexDashString.length == 36) {
                "Expected a 36-char string in the standard hex-and-dash UUID format, " +
                        "but was \"${hexDashString.truncateForErrorMessage(64)}\" of length ${hexDashString.length}"
            }
            return uuidParseHexDash(hexDashString)
        }

        public fun parseHex(hexString: String): Uuid {
            require(hexString.length == 32) {
                "Expected a 32-char hexadecimal string, " +
                        "but was \"${hexString.truncateForErrorMessage(64)}\" of length ${hexString.length}"
            }
            return uuidParseHex(hexString)
        }

        public fun random(): Uuid =
            secureRandomUuid()

        @Deprecated("Use naturalOrder<Uuid>() instead", ReplaceWith("naturalOrder<Uuid>()", imports = ["kotlin.comparisons.naturalOrder"]))
        @DeprecatedSinceKotlin(warningSince = "2.1")
        public val LEXICAL_ORDER: Comparator<Uuid>
            get() = naturalOrder()
    }
}
internal fun secureRandomUuid(): Uuid {
    val randomBytes = ByteArray(Uuid.SIZE_BYTES)
    kotlin.random.Random.Default.nextBytes(randomBytes)
    return uuidFromRandomBytes(randomBytes)
}
internal fun uuidFromRandomBytes(randomBytes: ByteArray): Uuid {
    randomBytes[6] = (randomBytes[6].toInt() and 0x0f).toByte() 
    randomBytes[6] = (randomBytes[6].toInt() or 0x40).toByte()  
    randomBytes[8] = (randomBytes[8].toInt() and 0x3f).toByte() 
    randomBytes[8] = (randomBytes[8].toInt() or 0x80).toByte()  
    return Uuid.fromByteArray(randomBytes)
}
internal fun ByteArray.getLongAt(index: Int): Long = getLongAtCommonImpl(index)

internal fun ByteArray.getLongAtCommonImpl(index: Int): Long {
    return ((this[index + 0].toLong() and 0xFF) shl 56) or
            ((this[index + 1].toLong() and 0xFF) shl 48) or
            ((this[index + 2].toLong() and 0xFF) shl 40) or
            ((this[index + 3].toLong() and 0xFF) shl 32) or
            ((this[index + 4].toLong() and 0xFF) shl 24) or
            ((this[index + 5].toLong() and 0xFF) shl 16) or
            ((this[index + 6].toLong() and 0xFF) shl 8) or
            (this[index + 7].toLong() and 0xFF)
}
internal fun Long.formatBytesInto(dst: ByteArray, dstOffset: Int, startIndex: Int, endIndex: Int) = formatBytesIntoCommonImpl(dst, dstOffset, startIndex, endIndex)
internal fun Long.formatBytesIntoCommonImpl(dst: ByteArray, dstOffset: Int, startIndex: Int, endIndex: Int) {
    var dstIndex = dstOffset
    for (reversedIndex in 7 - startIndex downTo 8 - endIndex) {
        val shift = reversedIndex shl 3
        val byte = ((this shr shift) and 0xFF).toInt()
        val byteDigits = BYTE_TO_LOWER_CASE_HEX_DIGITS[byte]
        dst[dstIndex++] = (byteDigits shr 8).toByte()
        dst[dstIndex++] = byteDigits.toByte()
    }
}

internal fun String.checkHyphenAt(index: Int) {
    require(this[index] == '-') { "Expected '-' (hyphen) at index $index, but was '${this[index]}'" }
}
internal fun ByteArray.setLongAt(index: Int, value: Long) = setLongAtCommonImpl(index, value)

internal fun ByteArray.setLongAtCommonImpl(index: Int, value: Long) {
    var i = index
    for (reversedIndex in 7 downTo 0) {
        val shift = reversedIndex shl 3
        this[i++] = (value shr shift).toByte()
    }
}
internal fun uuidParseHexDash(hexDashString: String): Uuid = uuidParseHexDashCommonImpl(hexDashString)
internal fun uuidParseHexDashCommonImpl(hexDashString: String): Uuid {
    val part1 = hexDashString.hexToLong(startIndex = 0, endIndex = 8)
    hexDashString.checkHyphenAt(8)
    val part2 = hexDashString.hexToLong(startIndex = 9, endIndex = 13)
    hexDashString.checkHyphenAt(13)
    val part3 = hexDashString.hexToLong(startIndex = 14, endIndex = 18)
    hexDashString.checkHyphenAt(18)
    val part4 = hexDashString.hexToLong(startIndex = 19, endIndex = 23)
    hexDashString.checkHyphenAt(23)
    val part5 = hexDashString.hexToLong(startIndex = 24, endIndex = 36)

    val msb = (part1 shl 32) or (part2 shl 16) or part3
    val lsb = (part4 shl 48) or part5
    return Uuid.fromLongs(msb, lsb)
}
internal fun uuidParseHex(hexString: String): Uuid = uuidParseHexCommonImpl(hexString)
internal fun uuidParseHexCommonImpl(hexString: String): Uuid {
    val msb = hexString.hexToLong(startIndex = 0, endIndex = 16)
    val lsb = hexString.hexToLong(startIndex = 16, endIndex = 32)
    return Uuid.fromLongs(msb, lsb)
}

private fun String.truncateForErrorMessage(maxLength: Int): String {
    return if (length <= maxLength) this else substring(0, maxLength) + "..."
}

private fun ByteArray.truncateForErrorMessage(maxSize: Int): String {
    return joinToString(prefix = "[", postfix = "]", limit = maxSize)
}
