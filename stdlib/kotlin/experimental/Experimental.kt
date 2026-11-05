/*
 * Opt-in markers of the Kotlin standard library that programs name in `@OptIn(...)`.
 */
package kotlin.experimental

import kotlin.annotation.AnnotationTarget.*

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(ANNOTATION_CLASS)
public annotation class ExperimentalTypeInference

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(CLASS, FUNCTION, PROPERTY, TYPEALIAS)
public annotation class ExperimentalNativeApi
