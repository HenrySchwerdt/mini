/*
 * enumValues / enumValueOf / enumEntries of kotlin-stdlib (kotlin/enums/EnumEntries.kt, kotlin/Enum.kt). The compiler replaces
 * a call (Ir2MirCalls) by the generated `values()` / `valueOf(name)` / `entries` of the enum class that `T` is.
 */
package kotlin

import kotlin.enums.EnumEntries

public inline fun <reified T : Enum<T>> enumValues(): Array<T> = throw UnsupportedOperationException("enumValues is lowered by the compiler")

public inline fun <reified T : Enum<T>> enumValueOf(name: String): T = throw UnsupportedOperationException("enumValueOf is lowered by the compiler")

public inline fun <reified T : Enum<T>> enumEntries(): EnumEntries<T> = throw UnsupportedOperationException("enumEntries is lowered by the compiler")
