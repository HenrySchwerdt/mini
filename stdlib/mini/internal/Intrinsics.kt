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

@CSymbol("mk_ref_array_length")
public external fun refArrayLength(array: Any): Int

// ---- failures ------------------------------------------------------------------------------
// Every `throw` of the stdlib goes through these functions. Natively they abort the program
// (`mk_throw_*`: "fatal: <Kind>: <message>" on stderr, exit code != 0) until the backend supports
// exceptions; then the bodies become `throw IllegalArgumentException(message)` etc. in this one place.
// On the JVM (unit tests) they throw the real exceptions.

@CSymbol("mk_throw_illegal_argument")
public external fun throwIllegalArgument(message: String?): Nothing

@CSymbol("mk_throw_illegal_state")
public external fun throwIllegalState(message: String?): Nothing

@CSymbol("mk_throw_index_out_of_bounds")
public external fun throwIndexOutOfBounds(message: String?): Nothing

@CSymbol("mk_throw_no_such_element")
public external fun throwNoSuchElement(message: String?): Nothing

@CSymbol("mk_throw_number_format")
public external fun throwNumberFormat(message: String?): Nothing

@CSymbol("mk_throw_unsupported_operation")
public external fun throwUnsupportedOperation(message: String?): Nothing

@CSymbol("mk_throw_not_implemented")
public external fun throwNotImplemented(message: String?): Nothing
