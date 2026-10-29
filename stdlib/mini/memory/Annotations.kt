package mini.memory

enum class Mode { Strict, Managed }

/** Memory mode of a file (`@file:MemoryMode(Mode.Strict)`) or, overriding the file, of one class. Default: Managed. */
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MemoryMode(val mode: Mode)

/** Ownership is transferred (parameter, return value, field). Type position: `fun f(b: @Owned Buf): @Owned Buf`. */
@Target(
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE,
)
@Retention(AnnotationRetention.BINARY)
annotation class Owned

/** Only lent: must not be stored, freed or returned as owned. Default for parameters. */
@Target(
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE,
)
@Retention(AnnotationRetention.BINARY)
annotation class Borrowed

/** Instances of this class are allocated from an allocator of the given type. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class AllocatedBy(val allocator: kotlin.reflect.KClass<out Allocator>)

/** The function must not allocate (M0205). */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.BINARY)
annotation class NoAlloc

/** The lambda parameter runs on another thread, exactly once; captured owned values are moved into it. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class ThreadBoundary
