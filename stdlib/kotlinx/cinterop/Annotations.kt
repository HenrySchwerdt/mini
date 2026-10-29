/*
 * C interop, the annotations (docs/plan-kotlin.md section 7, compiler/README.md "C interop").
 */
package kotlinx.cinterop

import kotlin.annotation.AnnotationTarget.*

/** `@file:CInclude("string.h")`: the C backend includes this header into the generated C file. */
@Target(FILE)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class CInclude(val header: String)

/** `@file:CLink("m")`: the program is linked with `-lm` (also given to the linker by `minic` and `mini`). */
@Target(FILE)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class CLink(val library: String)

/**
 * `@CStruct("timespec") external class Timespec : CStructVar { var tv_sec: Long; var tv_nsec: Long }`: a C struct.
 * The properties are the fields, in declaration order; the layout (offsets, size, alignment) is computed by the C rules
 * from their types. [size] / [align] override the computed values (trailing padding or fields that are not declared).
 */
@Target(CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CStruct(val name: String, val size: Long = -1L, val align: Int = -1)

/** A struct field that is an array stored in place (`char name[16]`): the property is a `CPointer` to the first element. */
@Target(PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class CArray(val length: Int)

/** An external function with a C `...` parameter list: the first [fixed] parameters are fixed, the others are passed as varargs. */
@Target(FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class CVariadic(val fixed: Int)
