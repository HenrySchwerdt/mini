/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Index checks and String -> CharArray copies shared by the hex and Base64 code (Kotlin/Native shapes).
 */
package kotlin.text

internal fun hexCheckBounds(startIndex: Int, endIndex: Int, size: Int) {
    if (startIndex < 0 || endIndex > size) throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, size: $size")
    if (startIndex > endIndex) throw IllegalArgumentException("startIndex: $startIndex > endIndex: $endIndex")
}

public fun String.toCharArray(destination: CharArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = length): CharArray {
    hexCheckBounds(startIndex, endIndex, length)
    if (destinationOffset < 0 || destinationOffset + (endIndex - startIndex) > destination.size) {
        throw IndexOutOfBoundsException("The destination array does not have enough capacity")
    }
    var i = startIndex
    var j = destinationOffset
    while (i < endIndex) {
        destination[j] = this[i]
        i++
        j++
    }
    return destination
}

public fun StringBuilder.toCharArray(destination: CharArray, destinationOffset: Int = 0, startIndex: Int = 0, endIndex: Int = length): CharArray {
    hexCheckBounds(startIndex, endIndex, length)
    if (destinationOffset < 0 || destinationOffset + (endIndex - startIndex) > destination.size) {
        throw IndexOutOfBoundsException("The destination array does not have enough capacity")
    }
    var i = startIndex
    var j = destinationOffset
    while (i < endIndex) {
        destination[j] = this[i]
        i++
        j++
    }
    return destination
}

public fun ByteArray.decodeToString(startIndex: Int = 0, endIndex: Int = this.size, throwOnInvalidSequence: Boolean): String {
    if (throwOnInvalidSequence) {
        var i = startIndex
        while (i < endIndex) {
            val b = this[i].toInt() and 0xFF
            val need = if (b < 0x80) 0 else if (b in 0xC2..0xDF) 1 else if (b in 0xE0..0xEF) 2 else if (b in 0xF0..0xF4) 3 else -1
            if (need < 0 || i + need >= endIndex) {
                throw CharacterCodingException("Malformed input")
            }
            var k = 1
            while (k <= need) {
                if ((this[i + k].toInt() and 0xC0) != 0x80) throw CharacterCodingException("Malformed input")
                k++
            }
            i += need + 1
        }
    }
    return decodeToString(startIndex, endIndex)
}

public open class CharacterCodingException(message: String?) : Exception(message) {
    public constructor() : this(null)
}
