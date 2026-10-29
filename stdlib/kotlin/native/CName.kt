/*
 * C interop: the C symbol of an external function (docs/plan-kotlin.md section 7).
 */
package kotlin.native

import kotlin.annotation.AnnotationTarget.*

/**
 * `@CName("strlen") external fun strlen(s: CPointer<ByteVar>): Long` calls the C function `strlen` directly.
 * (On a non-external function Kotlin/Native exports it under that C name; Mini does not do that yet.)
 */
@Target(FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
public annotation class CName(val externName: String = "", val shortName: String = "")
