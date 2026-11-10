/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.io.encoding.Base64 (ported from kotlin-stdlib commonMain).
 */
package kotlin.io.encoding

import kotlin.text.Appendable
public open class Base64 private constructor(
    internal val isUrlSafe: Boolean,
    internal val isMimeScheme: Boolean,
    internal val paddingOption: Base64.PaddingOption
) {
    init {
        require(!isUrlSafe || !isMimeScheme)
    }
    public enum class PaddingOption {

        PRESENT,

        ABSENT,

        PRESENT_OPTIONAL,

        ABSENT_OPTIONAL
    }
    public fun withPadding(option: Base64.PaddingOption): Base64 {
        return if (paddingOption == option) this
        else Base64(isUrlSafe, isMimeScheme, option)
    }

    public fun encodeToByteArray(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): ByteArray {
        return encodeToByteArrayImpl(source, startIndex, endIndex)
    }

    public fun encodeIntoByteArray(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): Int {
        return encodeIntoByteArrayImpl(source, destination, destinationOffset, startIndex, endIndex)
    }

    public fun encode(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): String {
        return bytesToStringImpl(encodeToByteArrayImpl(source, startIndex, endIndex))
    }

    public fun <A : Appendable> encodeToAppendable(
        source: ByteArray,
        destination: A,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): A {
        val stringResult = bytesToStringImpl(encodeToByteArrayImpl(source, startIndex, endIndex))
        destination.append(stringResult)
        return destination
    }

    public fun decode(source: ByteArray, startIndex: Int = 0, endIndex: Int = source.size): ByteArray {
        checkSourceBounds(source.size, startIndex, endIndex)

        val decodeSize = decodeSize(source, startIndex, endIndex)
        val destination = ByteArray(decodeSize)

        val bytesWritten = decodeImpl(source, destination, 0, startIndex, endIndex)

        check(bytesWritten == destination.size)

        return destination
    }

    public fun decodeIntoByteArray(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.size
    ): Int {
        checkSourceBounds(source.size, startIndex, endIndex)
        checkDestinationBounds(destination.size, destinationOffset, decodeSize(source, startIndex, endIndex))

        return decodeImpl(source, destination, destinationOffset, startIndex, endIndex)
    }

    public fun decode(source: CharSequence, startIndex: Int = 0, endIndex: Int = source.length): ByteArray {
        val byteSource = charsToBytesImpl(source, startIndex, endIndex)
        return decode(byteSource)
    }

    public fun decodeIntoByteArray(
        source: CharSequence,
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = source.length
    ): Int {
        val byteSource = charsToBytesImpl(source, startIndex, endIndex)
        return decodeIntoByteArray(byteSource, destination, destinationOffset)
    }

    internal fun encodeToByteArrayImpl(source: ByteArray, startIndex: Int, endIndex: Int): ByteArray {
        checkSourceBounds(source.size, startIndex, endIndex)

        val encodeSize = encodeSize(endIndex - startIndex)
        val destination = ByteArray(encodeSize)
        encodeIntoByteArrayImpl(source, destination, 0, startIndex, endIndex)
        return destination
    }

    internal fun encodeIntoByteArrayImpl(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int,
        startIndex: Int,
        endIndex: Int
    ): Int {
        checkSourceBounds(source.size, startIndex, endIndex)
        checkDestinationBounds(destination.size, destinationOffset, encodeSize(endIndex - startIndex))

        val encodeMap = if (isUrlSafe) base64UrlEncodeMap else base64EncodeMap
        var sourceIndex = startIndex
        var destinationIndex = destinationOffset
        val groupsPerLine = if (isMimeScheme) mimeGroupsPerLine else Int.MAX_VALUE

        while (sourceIndex + 2 < endIndex) {
            val groups = minOf((endIndex - sourceIndex) / bytesPerGroup, groupsPerLine)
            for (i in 0 until groups) {
                val byte1 = source[sourceIndex++].toInt() and 0xFF
                val byte2 = source[sourceIndex++].toInt() and 0xFF
                val byte3 = source[sourceIndex++].toInt() and 0xFF
                val bits = (byte1 shl 16) or (byte2 shl 8) or byte3
                destination[destinationIndex++] = encodeMap[bits ushr 18]
                destination[destinationIndex++] = encodeMap[(bits ushr 12) and 0x3F]
                destination[destinationIndex++] = encodeMap[(bits ushr 6) and 0x3F]
                destination[destinationIndex++] = encodeMap[bits and 0x3F]
            }
            if (groups == groupsPerLine && sourceIndex != endIndex) {
                destination[destinationIndex++] = mimeLineSeparatorSymbols[0]
                destination[destinationIndex++] = mimeLineSeparatorSymbols[1]
            }
        }

        when (endIndex - sourceIndex) {
            1 -> {
                val byte1 = source[sourceIndex++].toInt() and 0xFF
                val bits = byte1 shl 4
                destination[destinationIndex++] = encodeMap[bits ushr 6]
                destination[destinationIndex++] = encodeMap[bits and 0x3F]
                if (shouldPadOnEncode()) {
                    destination[destinationIndex++] = padSymbol
                    destination[destinationIndex++] = padSymbol
                }
            }
            2 -> {
                val byte1 = source[sourceIndex++].toInt() and 0xFF
                val byte2 = source[sourceIndex++].toInt() and 0xFF
                val bits = (byte1 shl 10) or (byte2 shl 2)
                destination[destinationIndex++] = encodeMap[bits ushr 12]
                destination[destinationIndex++] = encodeMap[(bits ushr 6) and 0x3F]
                destination[destinationIndex++] = encodeMap[bits and 0x3F]
                if (shouldPadOnEncode()) {
                    destination[destinationIndex++] = padSymbol
                }
            }
        }

        check(sourceIndex == endIndex)

        return destinationIndex - destinationOffset
    }
    internal fun encodeSize(sourceSize: Int): Int {
        val groups = sourceSize / bytesPerGroup
        val trailingBytes = sourceSize % bytesPerGroup
        var size = groups * symbolsPerGroup
        if (trailingBytes != 0) { // trailing symbols
            size += if (shouldPadOnEncode()) symbolsPerGroup else trailingBytes + 1
        }
        if (isMimeScheme) { // line separators
            size += ((size - 1) / mimeLineLength) * 2
        }
        if (size < 0) { // Int overflow
            throw IllegalArgumentException("Input is too big")
        }
        return size
    }

    private fun shouldPadOnEncode(): Boolean =
        paddingOption == PaddingOption.PRESENT || paddingOption == PaddingOption.PRESENT_OPTIONAL

    private fun decodeImpl(
        source: ByteArray,
        destination: ByteArray,
        destinationOffset: Int,
        startIndex: Int,
        endIndex: Int
    ): Int {
        val decodeMap = if (isUrlSafe) base64UrlDecodeMap else base64DecodeMap
        var payload = 0
        var byteStart = -bitsPerByte
        var sourceIndex = startIndex
        var destinationIndex = destinationOffset
        var hasPadding = false

        while (sourceIndex < endIndex) {
            if (byteStart == -bitsPerByte && sourceIndex + 3 < endIndex) {
                val symbol1 = decodeMap[source[sourceIndex++].toInt() and 0xFF]
                val symbol2 = decodeMap[source[sourceIndex++].toInt() and 0xFF]
                val symbol3 = decodeMap[source[sourceIndex++].toInt() and 0xFF]
                val symbol4 = decodeMap[source[sourceIndex++].toInt() and 0xFF]
                val bits = (symbol1 shl 18) or (symbol2 shl 12) or (symbol3 shl 6) or symbol4
                if (bits >= 0) { // all base64 symbols
                    destination[destinationIndex++] = (bits shr 16).toByte()
                    destination[destinationIndex++] = (bits shr 8).toByte()
                    destination[destinationIndex++] = bits.toByte()
                    continue
                }
                sourceIndex -= 4
            }

            val symbol = source[sourceIndex].toInt() and 0xFF
            val symbolBits = decodeMap[symbol]
            if (symbolBits < 0) {
                if (symbolBits == -2) {
                    hasPadding = true
                    sourceIndex = handlePaddingSymbol(source, sourceIndex, endIndex, byteStart)
                    break
                } else if (isMimeScheme) {
                    sourceIndex += 1
                    continue
                } else {
                    throw IllegalArgumentException("Invalid symbol '${symbol.toChar()}'(${symbol.toString(radix = 8)}) at index $sourceIndex")
                }
            } else {
                sourceIndex += 1
            }

            payload = (payload shl bitsPerSymbol) or symbolBits
            byteStart += bitsPerSymbol

            if (byteStart >= 0) {
                destination[destinationIndex++] = (payload ushr byteStart).toByte()

                payload = payload and ((1 shl byteStart) - 1)
                byteStart -= bitsPerByte
            }
        }

        if (byteStart == -bitsPerByte + bitsPerSymbol) { // dangling single symbol, incorrectly encoded
            throw IllegalArgumentException("The last unit of input does not have enough bits")
        }
        if (byteStart != -bitsPerByte && !hasPadding && paddingOption == PaddingOption.PRESENT) {
            throw IllegalArgumentException("The padding option is set to PRESENT, but the input is not properly padded")
        }
        if (payload != 0) { // the pad bits are non-zero
            throw IllegalArgumentException("The pad bits must be zeros")
        }

        sourceIndex = skipIllegalSymbolsIfMime(source, sourceIndex, endIndex)
        if (sourceIndex < endIndex) {
            val symbol = source[sourceIndex].toInt() and 0xFF
            throw IllegalArgumentException("Symbol '${symbol.toChar()}'(${symbol.toString(radix = 8)}) at index ${sourceIndex - 1} is prohibited after the pad character")
        }

        return destinationIndex - destinationOffset
    }
    internal fun decodeSize(source: ByteArray, startIndex: Int, endIndex: Int): Int {
        var symbols = endIndex - startIndex
        if (symbols == 0) {
            return 0
        }
        if (symbols == 1) {
            throw IllegalArgumentException("Input should have at least 2 symbols for Base64 decoding, startIndex: $startIndex, endIndex: $endIndex")
        }
        if (isMimeScheme) {
            for (index in startIndex until endIndex) {
                val symbol = source[index].toInt() and 0xFF
                val symbolBits = base64DecodeMap[symbol]
                if (symbolBits < 0) {
                    if (symbolBits == -2) {
                        symbols -= endIndex - index
                        break
                    }
                    symbols--
                }
            }
        } else if (source[endIndex - 1] == padSymbol) {
            symbols--
            if (source[endIndex - 2] == padSymbol) {
                symbols--
            }
        }
        return ((symbols.toLong() * bitsPerSymbol) / bitsPerByte).toInt() // conversion due to possible Int overflow
    }

    internal fun charsToBytesImpl(source: CharSequence, startIndex: Int, endIndex: Int): ByteArray {
        checkSourceBounds(source.length, startIndex, endIndex)

        val byteArray = ByteArray(endIndex - startIndex)
        var length = 0
        for (index in startIndex until endIndex) {
            val symbol = source[index].code
            if (symbol <= 0xFF) {
                byteArray[length++] = symbol.toByte()
            } else {
                byteArray[length++] = 0x3F
            }
        }
        return byteArray
    }

    internal fun bytesToStringImpl(source: ByteArray): String {
        val stringBuilder = StringBuilder(source.size)
        for (byte in source) {
            stringBuilder.append(byte.toInt().toChar())
        }
        return stringBuilder.toString()
    }

    private fun handlePaddingSymbol(source: ByteArray, padIndex: Int, endIndex: Int, byteStart: Int): Int {
        return when (byteStart) {
            -bitsPerByte -> // =
                throw IllegalArgumentException("Redundant pad character at index $padIndex")
            -bitsPerByte + bitsPerSymbol -> // x=, dangling single symbol
                padIndex + 1
            -bitsPerByte + 2 * bitsPerSymbol - bitsPerByte -> { // xx=
                checkPaddingIsAllowed(padIndex)
                val secondPadIndex = skipIllegalSymbolsIfMime(source, padIndex + 1, endIndex)
                if (secondPadIndex == endIndex || source[secondPadIndex] != padSymbol) {
                    throw IllegalArgumentException("Missing one pad character at index $secondPadIndex")
                }
                secondPadIndex + 1
            }
            -bitsPerByte + 3 * bitsPerSymbol - 2 * bitsPerByte -> { // xxx=
                checkPaddingIsAllowed(padIndex)
                padIndex + 1
            }
            else ->
                error("Unreachable")
        }
    }

    private fun checkPaddingIsAllowed(padIndex: Int) {
        if (paddingOption == PaddingOption.ABSENT) {
            throw IllegalArgumentException(
                "The padding option is set to ABSENT, but the input has a pad character at index $padIndex"
            )
        }
    }

    private fun skipIllegalSymbolsIfMime(source: ByteArray, startIndex: Int, endIndex: Int): Int {
        if (!isMimeScheme) {
            return startIndex
        }
        var sourceIndex = startIndex
        while (sourceIndex < endIndex) {
            val symbol = source[sourceIndex].toInt() and 0xFF
            if (base64DecodeMap[symbol] != -1) {
                return sourceIndex
            }
            sourceIndex += 1
        }
        return sourceIndex
    }

    internal fun checkSourceBounds(sourceSize: Int, startIndex: Int, endIndex: Int) {
        if (startIndex < 0 || endIndex > sourceSize) throw IndexOutOfBoundsException("startIndex: $startIndex, endIndex: $endIndex, size: $sourceSize")
        if (startIndex > endIndex) throw IllegalArgumentException("startIndex: $startIndex > endIndex: $endIndex")
    }

    private fun checkDestinationBounds(destinationSize: Int, destinationOffset: Int, capacityNeeded: Int) {
        if (destinationOffset < 0 || destinationOffset > destinationSize) {
            throw IndexOutOfBoundsException("destination offset: $destinationOffset, destination size: $destinationSize")
        }

        val destinationEndIndex = destinationOffset + capacityNeeded
        if (destinationEndIndex < 0 || destinationEndIndex > destinationSize) {
            throw IndexOutOfBoundsException(
                "The destination array does not have enough capacity, " +
                        "destination offset: $destinationOffset, destination size: $destinationSize, capacity needed: $capacityNeeded"
            )
        }
    }

    public companion object Default : Base64(isUrlSafe = false, isMimeScheme = false, PaddingOption.PRESENT) {

        private const val bitsPerByte: Int = 8
        private const val bitsPerSymbol: Int = 6

        internal const val bytesPerGroup: Int = 3
        internal const val symbolsPerGroup: Int = 4

        internal const val padSymbol: Byte = 61 // '='

        internal const val mimeLineLength: Int = 76
        private const val mimeGroupsPerLine: Int = mimeLineLength / symbolsPerGroup
        internal val mimeLineSeparatorSymbols: ByteArray = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        public val UrlSafe: Base64 = Base64(isUrlSafe = true, isMimeScheme = false, PaddingOption.PRESENT)

        public val Mime: Base64 = Base64(isUrlSafe = false, isMimeScheme = true, PaddingOption.PRESENT)
    }
}
private val base64EncodeMap = byteArrayOf(
    65,  66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,  77,  78,  79,  80,  
    81,  82,  83,  84,  85,  86,  87,  88,  89,  90,  97,  98,  99,  100, 101, 102, 
    103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 
    119, 120, 121, 122, 48,  49,  50,  51,  52,  53,  54,  55,  56,  57,  43,  47,  
)
private val base64DecodeMap = IntArray(256).apply {
    this.fill(-1)
    this[Base64.padSymbol.toInt()] = -2
    base64EncodeMap.forEachIndexed { index, symbol ->
        this[symbol.toInt()] = index
    }
}
private val base64UrlEncodeMap = byteArrayOf(
    65,  66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,  77,  78,  79,  80,  
    81,  82,  83,  84,  85,  86,  87,  88,  89,  90,  97,  98,  99,  100, 101, 102, 
    103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 
    119, 120, 121, 122, 48,  49,  50,  51,  52,  53,  54,  55,  56,  57,  45,  95,  
)
private val base64UrlDecodeMap = IntArray(256).apply {
    this.fill(-1)
    this[Base64.padSymbol.toInt()] = -2
    base64UrlEncodeMap.forEachIndexed { index, symbol ->
        this[symbol.toInt()] = index
    }
}
