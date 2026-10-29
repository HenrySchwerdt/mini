package mini.internal

/*
 * The complete native surface of the Mini stdlib (besides the builtin members and kotlin.io).
 * Everything else in stdlib is plain Kotlin on top of these functions.
 *
 * Convention: see CSymbol. `Any` handles (string builders, ref arrays) are opaque runtime objects.
 * The JVM test harness (stdlib-test/jvm/mini/internal/Intrinsics.kt) provides the same
 * functions in Kotlin, so the stdlib logic can be tested on the JVM; a test keeps both files in sync.
 */

/** Fully qualified Kotlin name of the runtime type of [o] (`MkTypeInfo.name`). */
@CSymbol("mk_type_name")
public external fun typeName(o: Any): String

/** Address based hash, the default `Any.hashCode`. */
@CSymbol("mk_identity_hash")
public external fun identityHashCode(o: Any?): Int

// ---- strings -----------------------------------------------------------------------------

/** UTF-16 code units `[start, end)` of [s]; bounds are checked by the caller. */
@CSymbol("mk_string_substring")
public external fun stringSubstring(s: String, start: Int, end: Int): String

/** `strtod`-like parse of the whole string (Kotlin `toDouble` syntax); only meaningful if [stringIsDouble]. */
@CSymbol("mk_string_parse_double")
public external fun stringParseDouble(s: String): Double

@CSymbol("mk_string_is_double")
public external fun stringIsDouble(s: String): Boolean

// ---- StringBuilder storage ---------------------------------------------------------------

@CSymbol("mk_sb_new")
public external fun sbNew(): Any

@CSymbol("mk_sb_append_string")
public external fun sbAppendString(sb: Any, s: String)

@CSymbol("mk_sb_append_char")
public external fun sbAppendChar(sb: Any, c: Char)

@CSymbol("mk_sb_length")
public external fun sbLength(sb: Any): Int

@CSymbol("mk_sb_char_at")
public external fun sbCharAt(sb: Any, index: Int): Char

@CSymbol("mk_sb_set_char_at")
public external fun sbSetCharAt(sb: Any, index: Int, c: Char)

/** Truncates (or pads with NUL characters) to [length]. */
@CSymbol("mk_sb_set_length")
public external fun sbSetLength(sb: Any, length: Int)

@CSymbol("mk_sb_to_string")
public external fun sbToString(sb: Any): String

// ---- reference arrays (storage of the collections) -----------------------------------------

/** A fixed-size array of `Any?` slots, all null. Elements are retained by the array. */
@CSymbol("mk_ref_array_new")
public external fun refArrayNew(size: Int): Any

@CSymbol("mk_ref_array_get")
public external fun refArrayGet(array: Any, index: Int): Any?

@CSymbol("mk_ref_array_set")
public external fun refArraySet(array: Any, index: Int, value: Any?)

/**
 * Like [refArrayGet] but borrowed: no reference is added, the result is valid as long as the array keeps the slot
 * (docs/benchmarks.md D2: `ReturnsBorrowed`).
 */
@CSymbol("mk_ref_array_get_b")
public external fun refArrayGetBorrowed(array: Any, index: Int): Any?

/** Like [refArraySet] but the caller hands over its reference to [value] (`TakesOwnership`): no retain. */
@CSymbol("mk_ref_array_set_o")
public external fun refArraySetOwned(array: Any, index: Int, value: Any?)

@CSymbol("mk_ref_array_length")
public external fun refArrayLength(array: Any): Int

// ---- failures ------------------------------------------------------------------------------
// Every `throw` of the stdlib goes through these functions (one seam). The backend supports exceptions
// natively (error-return ABI, see compiler/README.md), so they are ordinary Kotlin `throw`s. The native
// runtime also keeps the aborting `mk_throw_*` symbols (v1-S block of minirt.h) for direct use.

public fun throwIllegalArgument(message: String?): Nothing = throw IllegalArgumentException(message)

public fun throwIllegalState(message: String?): Nothing = throw IllegalStateException(message)

public fun throwIndexOutOfBounds(message: String?): Nothing = throw IndexOutOfBoundsException(message)

public fun throwNoSuchElement(message: String?): Nothing = throw NoSuchElementException(message)

public fun throwNumberFormat(message: String?): Nothing = throw NumberFormatException(message)

public fun throwUnsupportedOperation(message: String?): Nothing = throw UnsupportedOperationException(message)

public fun throwNotImplemented(message: String?): Nothing =
    throw (if (message == null) NotImplementedError() else NotImplementedError(message))
