/*
 * Ported from kotlin-stdlib generated/_Arrays.kt and _UArrays.kt: `withIndex()` of the array types that PrimitiveArrayFunctions.kt
 * does not cover (Byte, Short and the unsigned arrays).
 */
package kotlin.collections

public fun ByteArray.withIndex(): Iterable<IndexedValue<Byte>> {
    val list = ArrayList<IndexedValue<Byte>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}

public fun ShortArray.withIndex(): Iterable<IndexedValue<Short>> {
    val list = ArrayList<IndexedValue<Short>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}

public fun UByteArray.withIndex(): Iterable<IndexedValue<UByte>> {
    val list = ArrayList<IndexedValue<UByte>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}

public fun UShortArray.withIndex(): Iterable<IndexedValue<UShort>> {
    val list = ArrayList<IndexedValue<UShort>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}

public fun UIntArray.withIndex(): Iterable<IndexedValue<UInt>> {
    val list = ArrayList<IndexedValue<UInt>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}

public fun ULongArray.withIndex(): Iterable<IndexedValue<ULong>> {
    val list = ArrayList<IndexedValue<ULong>>(size)
    var i = 0
    while (i < size) {
        list.add(IndexedValue(i, this[i]))
        i++
    }
    return list
}
