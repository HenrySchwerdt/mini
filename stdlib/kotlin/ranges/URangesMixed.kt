/*
 * Mini stdlib: `contains` of unsigned ranges for values of the other unsigned types (kotlin-stdlib generated/_URanges.kt).
 */
package kotlin.ranges

public operator fun ClosedRange<UInt>.contains(value: UByte): Boolean = contains(value.toUInt())
public operator fun ClosedRange<UInt>.contains(value: UShort): Boolean = contains(value.toUInt())
public operator fun ClosedRange<UInt>.contains(value: ULong): Boolean = value <= UInt.MAX_VALUE.toULong() && contains(value.toUInt())

public operator fun ClosedRange<ULong>.contains(value: UByte): Boolean = contains(value.toULong())
public operator fun ClosedRange<ULong>.contains(value: UShort): Boolean = contains(value.toULong())
public operator fun ClosedRange<ULong>.contains(value: UInt): Boolean = contains(value.toULong())

public operator fun ClosedRange<UByte>.contains(value: UShort): Boolean = value <= UByte.MAX_VALUE.toUShort() && contains(value.toUByte())
public operator fun ClosedRange<UByte>.contains(value: UInt): Boolean = value <= UByte.MAX_VALUE.toUInt() && contains(value.toUByte())
public operator fun ClosedRange<UByte>.contains(value: ULong): Boolean = value <= UByte.MAX_VALUE.toULong() && contains(value.toUByte())

public operator fun ClosedRange<UShort>.contains(value: UByte): Boolean = contains(value.toUShort())
public operator fun ClosedRange<UShort>.contains(value: UInt): Boolean = value <= UShort.MAX_VALUE.toUInt() && contains(value.toUShort())
public operator fun ClosedRange<UShort>.contains(value: ULong): Boolean = value <= UShort.MAX_VALUE.toULong() && contains(value.toUShort())
