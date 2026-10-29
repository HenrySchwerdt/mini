/*
 * C interop: pointers and variables (docs/plan-kotlin.md section 7, compiler/README.md "C interop").
 * API of Kotlin/Native's kotlinx.cinterop; ordinary Kotlin on top of the intrinsics of Intrinsics.kt.
 */
package kotlinx.cinterop

// ---- variables: `.value` ------------------------------------------------------------------------------------------

public var ByteVar.value: Byte
    get() = mkLoadByte(rawPtr, 0L)
    set(value) { mkStoreByte(rawPtr, 0L, value) }

public var ShortVar.value: Short
    get() = mkLoadShort(rawPtr, 0L)
    set(value) { mkStoreShort(rawPtr, 0L, value) }

public var IntVar.value: Int
    get() = mkLoadInt(rawPtr, 0L)
    set(value) { mkStoreInt(rawPtr, 0L, value) }

public var LongVar.value: Long
    get() = mkLoadLong(rawPtr, 0L)
    set(value) { mkStoreLong(rawPtr, 0L, value) }

public var FloatVar.value: Float
    get() = mkLoadFloat(rawPtr, 0L)
    set(value) { mkStoreFloat(rawPtr, 0L, value) }

public var DoubleVar.value: Double
    get() = mkLoadDouble(rawPtr, 0L)
    set(value) { mkStoreDouble(rawPtr, 0L, value) }

public var BooleanVar.value: Boolean
    get() = mkLoadBoolean(rawPtr, 0L)
    set(value) { mkStoreBoolean(rawPtr, 0L, value) }

public var <T : CPointed> CPointerVar<T>.value: CPointer<T>?
    get() = interpretCPointer<T>(mkLoadPtr(rawPtr, 0L))
    set(value) { mkStorePtr(rawPtr, 0L, value?.rawValue) }

// ---- pointers <-> pointed ----------------------------------------------------------------------------------------------

/** The value a pointer points to (a variable, a struct): the same address, typed as the pointee. */
public val <T : CPointed> CPointer<T>.pointed: T
    get() = interpretPointed<T>(rawValue)

/** The pointer to a pointed value. */
public val <T : CPointed> T.ptr: CPointer<T>
    get() = mkCPointer<T>(rawPtr)

/** The same address with another pointee type (a cast in C). */
public fun <T : CPointed> CPointer<*>.reinterpret(): CPointer<T> = mkCPointer<T>(rawValue)

public fun <T : CPointed> CPointed.reinterpret(): T = interpretPointed<T>(rawPtr)

public fun <T : CPointed> Long.toCPointer(): CPointer<T>? = interpretCPointer<T>(if (this == 0L) null else this.toNativePtr())

public fun CPointer<*>?.toLong(): Long = if (this == null) 0L else rawValue.toLong()

// ---- arithmetic: counted in elements of the pointee ----------------------------------------------------------------------

public inline operator fun <reified T : CVariable> CPointer<T>?.plus(index: Long): CPointer<T>? {
    if (this == null) {
        if (index == 0L) return null
        throw NullPointerException()
    }
    return interpretCPointer<T>(this.rawValue + index * sizeOf<T>())
}

public inline operator fun <reified T : CVariable> CPointer<T>?.plus(index: Int): CPointer<T>? = this + index.toLong()

public inline operator fun <reified T : CVariable> CPointer<T>.minus(index: Long): CPointer<T> =
    interpretCPointer<T>(this.rawValue + (-index) * sizeOf<T>())!!

public inline operator fun <reified T : CVariable> CPointer<T>.minus(index: Int): CPointer<T> = this - index.toLong()

/** The distance of two pointers in elements. */
public inline operator fun <reified T : CVariable> CPointer<T>.minus(other: CPointer<T>): Long =
    (this.rawValue.toLong() - other.rawValue.toLong()) / sizeOf<T>()

// ---- element access: `p[i]` -------------------------------------------------------------------------------------------------

public operator fun CPointer<ByteVar>.get(index: Long): Byte = mkLoadByte(rawValue, index)
public operator fun CPointer<ByteVar>.get(index: Int): Byte = mkLoadByte(rawValue, index.toLong())
public operator fun CPointer<ByteVar>.set(index: Long, value: Byte) { mkStoreByte(rawValue, index, value) }
public operator fun CPointer<ByteVar>.set(index: Int, value: Byte) { mkStoreByte(rawValue, index.toLong(), value) }

public operator fun CPointer<ShortVar>.get(index: Long): Short = mkLoadShort(rawValue, index * 2L)
public operator fun CPointer<ShortVar>.get(index: Int): Short = mkLoadShort(rawValue, index * 2L)
public operator fun CPointer<ShortVar>.set(index: Long, value: Short) { mkStoreShort(rawValue, index * 2L, value) }
public operator fun CPointer<ShortVar>.set(index: Int, value: Short) { mkStoreShort(rawValue, index * 2L, value) }

public operator fun CPointer<IntVar>.get(index: Long): Int = mkLoadInt(rawValue, index * 4L)
public operator fun CPointer<IntVar>.get(index: Int): Int = mkLoadInt(rawValue, index * 4L)
public operator fun CPointer<IntVar>.set(index: Long, value: Int) { mkStoreInt(rawValue, index * 4L, value) }
public operator fun CPointer<IntVar>.set(index: Int, value: Int) { mkStoreInt(rawValue, index * 4L, value) }

public operator fun CPointer<LongVar>.get(index: Long): Long = mkLoadLong(rawValue, index * 8L)
public operator fun CPointer<LongVar>.get(index: Int): Long = mkLoadLong(rawValue, index * 8L)
public operator fun CPointer<LongVar>.set(index: Long, value: Long) { mkStoreLong(rawValue, index * 8L, value) }
public operator fun CPointer<LongVar>.set(index: Int, value: Long) { mkStoreLong(rawValue, index * 8L, value) }

public operator fun CPointer<FloatVar>.get(index: Long): Float = mkLoadFloat(rawValue, index * 4L)
public operator fun CPointer<FloatVar>.get(index: Int): Float = mkLoadFloat(rawValue, index * 4L)
public operator fun CPointer<FloatVar>.set(index: Long, value: Float) { mkStoreFloat(rawValue, index * 4L, value) }
public operator fun CPointer<FloatVar>.set(index: Int, value: Float) { mkStoreFloat(rawValue, index * 4L, value) }

public operator fun CPointer<DoubleVar>.get(index: Long): Double = mkLoadDouble(rawValue, index * 8L)
public operator fun CPointer<DoubleVar>.get(index: Int): Double = mkLoadDouble(rawValue, index * 8L)
public operator fun CPointer<DoubleVar>.set(index: Long, value: Double) { mkStoreDouble(rawValue, index * 8L, value) }
public operator fun CPointer<DoubleVar>.set(index: Int, value: Double) { mkStoreDouble(rawValue, index * 8L, value) }

public operator fun CPointer<BooleanVar>.get(index: Long): Boolean = mkLoadBoolean(rawValue, index)
public operator fun CPointer<BooleanVar>.get(index: Int): Boolean = mkLoadBoolean(rawValue, index.toLong())
public operator fun CPointer<BooleanVar>.set(index: Long, value: Boolean) { mkStoreBoolean(rawValue, index, value) }
public operator fun CPointer<BooleanVar>.set(index: Int, value: Boolean) { mkStoreBoolean(rawValue, index.toLong(), value) }

public operator fun <T : CPointed> CPointer<CPointerVar<T>>.get(index: Long): CPointer<T>? =
    interpretCPointer<T>(mkLoadPtr(rawValue, index * 8L))
public operator fun <T : CPointed> CPointer<CPointerVar<T>>.get(index: Int): CPointer<T>? =
    interpretCPointer<T>(mkLoadPtr(rawValue, index * 8L))
public operator fun <T : CPointed> CPointer<CPointerVar<T>>.set(index: Long, value: CPointer<T>?) {
    mkStorePtr(rawValue, index * 8L, value?.rawValue)
}
public operator fun <T : CPointed> CPointer<CPointerVar<T>>.set(index: Int, value: CPointer<T>?) {
    mkStorePtr(rawValue, index * 8L, value?.rawValue)
}

/** The i-th element of an array of structs (or of any other variable type): a pointed value, not a copy. */
public inline operator fun <reified T : CStructVar> CPointer<T>.get(index: Long): T = interpretPointed<T>(rawValue + index * sizeOf<T>())

public inline operator fun <reified T : CStructVar> CPointer<T>.get(index: Int): T = this[index.toLong()]
