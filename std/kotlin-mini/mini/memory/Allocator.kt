package mini.memory

/**
 * Kotlin/Mini memory API (spec: docs/memory-model.md).
 *
 * This source is valid Kotlin and is compiled with plain kotlinc on the JVM, where every
 * object lives on the GC heap and the implementations below are naive or no-ops. The Mini
 * compiler treats these declarations specially: allocation sites are routed to the
 * allocators, and the ownership analysis gives `free`, `use`, `@Owned` ... their meaning.
 */

/** Opaque native address. On the JVM it is only a number. */
@JvmInline
value class NativePtr(val raw: Long)

/** Anything that can hand out memory. The capability traits below tell the compiler what to check. */
interface Allocator {
    fun allocate(size: Long, align: Int): NativePtr?
}

/** Objects are released one by one: must-free analysis (M0102, M0103, M0101). */
interface Deallocating : Allocator {
    fun free(ptr: NativePtr)

    /** `alloc.free(x)`: same as `x.free()`, but names the allocator (checked: M0112). */
    fun free(obj: Any?) {
        obj.free()
    }
}

/** Everything dies together when the scope ends: escape analysis (M0105, M0106). */
interface Scoped : Allocator, AutoCloseable

/** Reference counting, no obligations. Default for foreign Kotlin code. */
interface Managed : Allocator

/** Marker: objects of this allocator may be used from several threads (M0206). */
interface ThreadSafe

/**
 * THE way to end ownership (docs/memory-model.md, section 3). Accepts `null`.
 * If the object is [AutoCloseable], `close()` runs first. The compiler additionally
 * releases all owned fields of the object.
 */
fun Any?.free() {
    if (this is AutoCloseable) close()
}
