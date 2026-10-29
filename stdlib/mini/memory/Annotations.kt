package mini.memory

enum class Mode { Strict, Managed }

/** Memory mode of a file (`@file:MemoryMode(Mode.Strict)`) or, overriding the file, of one class. Default: Managed. */
@Target(AnnotationTarget.FILE, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MemoryMode(val mode: Mode)

/**
 * Ownership is transferred (parameter, return value, field). Type position: `fun f(b: @Owned Buf): @Owned Buf`.
 *
 * On an `external fun` of C (docs/plan-kotlin.md 7): `@Owned(free = "uv_loop_close") external fun make(): CPointer<...>?` says the
 * result is a C allocation the caller owns: the checker requires it to be released by calling the C function [free] with it
 * (or by `nativeHeap.free` when it came from `malloc`), like any other `Deallocating` allocation (M0102 / M0103 / M0101).
 * Pointers from C functions without `@Owned` are externally managed: no obligation.
 */
@Target(
    AnnotationTarget.TYPE, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.LOCAL_VARIABLE,
)
@Retention(AnnotationRetention.BINARY)
annotation class Owned(val free: String = "")

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

/**
 * Mini only: the function is implemented by the C function [name] of libminirt (the call is compiled to a plain C call,
 * the Kotlin body is the fallback for the JVM, where allocators are naive). Used for the allocator handles below.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class NativeSymbol(val name: String)
