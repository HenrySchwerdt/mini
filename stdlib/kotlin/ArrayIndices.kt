/*
 * Mini stdlib: `array.indices`. Builtin file (analysed, never converted): `for (i in array.indices)` is
 * pattern-matched by the backend and never calls these getters (docs/stdlib.md).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "EXTENSION_PROPERTY_MUST_HAVE_ACCESSORS_OR_BE_ABSTRACT")
@file:kotlin.internal.Builtin

package kotlin

public val Array<*>.indices: IntRange
public val IntArray.indices: IntRange
public val LongArray.indices: IntRange
public val DoubleArray.indices: IntRange
public val BooleanArray.indices: IntRange
public val CharArray.indices: IntRange
