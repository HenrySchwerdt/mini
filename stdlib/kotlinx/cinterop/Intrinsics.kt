/*
 * C interop, the intrinsics: the few operations the compiler implements itself (docs/plan-kotlin.md section 7).
 * Everything else of kotlinx.cinterop is ordinary Kotlin on top of them (Pointers.kt, Memory.kt, Strings.kt).
 *
 * Builtin file: no bodies, never translated; a call becomes the MIR operation named in the comment.
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "NON_MEMBER_FUNCTION_NO_BODY", "EXPECTED_DECLARATION_WITH_BODY", "WRONG_MODIFIER_TARGET")
@file:kotlin.internal.Builtin

package kotlinx.cinterop

// ---- addresses ------------------------------------------------------------------------------------------------

/** The address of a pointed value / of a pointer: the same bits, retyped. */
public external val CPointed.rawPtr: NativePtr

public external val CPointer<*>.rawValue: NativePtr

/** `ptr + offset` bytes. */
public external operator fun NativePtr.plus(offset: Long): NativePtr

public external fun NativePtr.toLong(): Long

public external fun Long.toNativePtr(): NativePtr

public external fun <T : CPointed> interpretPointed(ptr: NativePtr): T

public external fun <T : CPointed> interpretNullablePointed(ptr: NativePtr?): T?

public external fun <T : CPointed> interpretCPointer(rawValue: NativePtr?): CPointer<T>?

/** [interpretCPointer] for an address known not to be null (no check). */
@PublishedApi
internal external fun <T : CPointed> mkCPointer(ptr: NativePtr): CPointer<T>

/** The size and alignment of the C type of a variable class (`sizeOf<IntVar>()` is 4, of a `@CStruct` class its layout). */
public external fun <T : CVariable> sizeOf(): Long

public external fun <T : CVariable> alignOf(): Long

// ---- memory ---------------------------------------------------------------------------------------------------

internal external fun mkLoadByte(p: NativePtr, offset: Long): Byte
internal external fun mkLoadShort(p: NativePtr, offset: Long): Short
internal external fun mkLoadInt(p: NativePtr, offset: Long): Int
internal external fun mkLoadLong(p: NativePtr, offset: Long): Long
internal external fun mkLoadFloat(p: NativePtr, offset: Long): Float
internal external fun mkLoadDouble(p: NativePtr, offset: Long): Double
internal external fun mkLoadBoolean(p: NativePtr, offset: Long): Boolean
internal external fun mkLoadPtr(p: NativePtr, offset: Long): NativePtr?

internal external fun mkStoreByte(p: NativePtr, offset: Long, value: Byte)
internal external fun mkStoreShort(p: NativePtr, offset: Long, value: Short)
internal external fun mkStoreInt(p: NativePtr, offset: Long, value: Int)
internal external fun mkStoreLong(p: NativePtr, offset: Long, value: Long)
internal external fun mkStoreFloat(p: NativePtr, offset: Long, value: Float)
internal external fun mkStoreDouble(p: NativePtr, offset: Long, value: Double)
internal external fun mkStoreBoolean(p: NativePtr, offset: Long, value: Boolean)
internal external fun mkStorePtr(p: NativePtr, offset: Long, value: NativePtr?)

// ---- function pointers ------------------------------------------------------------------------------------------

/**
 * The address of a C function made from a lambda without captures (or a reference to a top-level function): a function
 * pointer a C library can call. The lambda runs with the C ABI; an exception that escapes it ends the program.
 */
public external fun <R> staticCFunction(function: () -> R): CPointer<CFunction<() -> R>>
public external fun <P1, R> staticCFunction(function: (P1) -> R): CPointer<CFunction<(P1) -> R>>
public external fun <P1, P2, R> staticCFunction(function: (P1, P2) -> R): CPointer<CFunction<(P1, P2) -> R>>
public external fun <P1, P2, P3, R> staticCFunction(function: (P1, P2, P3) -> R): CPointer<CFunction<(P1, P2, P3) -> R>>
public external fun <P1, P2, P3, P4, R> staticCFunction(function: (P1, P2, P3, P4) -> R): CPointer<CFunction<(P1, P2, P3, P4) -> R>>
public external fun <P1, P2, P3, P4, P5, R> staticCFunction(function: (P1, P2, P3, P4, P5) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5) -> R>>
public external fun <P1, P2, P3, P4, P5, P6, R> staticCFunction(function: (P1, P2, P3, P4, P5, P6) -> R): CPointer<CFunction<(P1, P2, P3, P4, P5, P6) -> R>>

/** Calls a C function pointer. */
public external operator fun <R> CPointer<CFunction<() -> R>>.invoke(): R
public external operator fun <P1, R> CPointer<CFunction<(P1) -> R>>.invoke(p1: P1): R
public external operator fun <P1, P2, R> CPointer<CFunction<(P1, P2) -> R>>.invoke(p1: P1, p2: P2): R
public external operator fun <P1, P2, P3, R> CPointer<CFunction<(P1, P2, P3) -> R>>.invoke(p1: P1, p2: P2, p3: P3): R
public external operator fun <P1, P2, P3, P4, R> CPointer<CFunction<(P1, P2, P3, P4) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4): R
public external operator fun <P1, P2, P3, P4, P5, R> CPointer<CFunction<(P1, P2, P3, P4, P5) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5): R
public external operator fun <P1, P2, P3, P4, P5, P6, R> CPointer<CFunction<(P1, P2, P3, P4, P5, P6) -> R>>.invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6): R
