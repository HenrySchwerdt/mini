/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.text.UHexExtensions (ported from kotlin-stdlib commonMain).
 */
package kotlin.text

public inline fun UByteArray.toHexString(format: HexFormat = HexFormat.Default): String = storage.toHexString(format)
public inline fun UByteArray.toHexString(
    startIndex: Int = 0,
    endIndex: Int = size,
    format: HexFormat = HexFormat.Default
): String = storage.toHexString(startIndex, endIndex, format)
public inline fun String.hexToUByteArray(format: HexFormat = HexFormat.Default): UByteArray =
    hexToByteArray(format).asUByteArray()
public inline fun UByte.toHexString(format: HexFormat = HexFormat.Default): String = data.toHexString(format)
public inline fun String.hexToUByte(format: HexFormat = HexFormat.Default): UByte = hexToByte(format).toUByte()
public inline fun UShort.toHexString(format: HexFormat = HexFormat.Default): String = data.toHexString(format)
public inline fun String.hexToUShort(format: HexFormat = HexFormat.Default): UShort = hexToShort(format).toUShort()
public inline fun UInt.toHexString(format: HexFormat = HexFormat.Default): String = data.toHexString(format)
public inline fun String.hexToUInt(format: HexFormat = HexFormat.Default): UInt = hexToInt(format).toUInt()
public inline fun ULong.toHexString(format: HexFormat = HexFormat.Default): String = data.toHexString(format)
public inline fun String.hexToULong(format: HexFormat = HexFormat.Default): ULong = hexToLong(format).toULong()
