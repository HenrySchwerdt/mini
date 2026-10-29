package mini.memory

/*
 * The allocators. Each owns a `handle`: on Mini the address of the runtime allocator (`MkAllocator *`, see
 * runtime/include/minirt.h) that the compiler passes to `mk_alloc_with` for allocation sites it routes here
 * (docs/memory-model.md 2.2); on the JVM the handle is 0 and everything is naive.
 */

@NativeSymbol("mk_heap_allocator_h")
internal fun nativeHeap(): Long = 0L

@NativeSymbol("mk_arena_new_h")
internal fun nativeArenaNew(): Long = 0L

@NativeSymbol("mk_arena_close_h")
internal fun nativeArenaClose(handle: Long) {}

@NativeSymbol("mk_pool_close_h")
internal fun nativePoolClose(handle: Long) {}

@NativeSymbol("mk_debug_new_h")
internal fun nativeDebugNew(inner: Long): Long = 0L

/** malloc/free. Stateless: creating a `Heap()` is free and carries no obligation. */
class Heap : Deallocating, ThreadSafe {
    @PublishedApi
    internal val handle: Long = nativeHeap()

    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
    override fun free(ptr: NativePtr) {}
}

/** Region allocator. It is a resource itself: it must be closed (`use {}`), see M0102. */
class Arena : Scoped {
    private var used = 0L
    val bytesUsed: Long get() = used

    @PublishedApi
    internal var handle: Long = nativeArenaNew()

    override fun allocate(size: Long, align: Int): NativePtr? {
        used += size
        return NativePtr(used)
    }

    override fun close() {
        used = 0
        if (handle != 0L) {
            nativeArenaClose(handle)
            handle = 0L
        }
    }
}

/** Fixed-capacity pool of objects of one type. Not [ThreadSafe]. A resource like [Arena]: close it. */
class Pool<T : Any>(val capacity: Int) : Deallocating, AutoCloseable {
    /** Set by the compiler at the construction site (the block size depends on `T`). */
    @PublishedApi
    internal var handle: Long = 0L

    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
    override fun free(ptr: NativePtr) {}
    override fun close() {
        if (handle != 0L) {
            nativePoolClose(handle)
            handle = 0L
        }
    }
}

/** Reference counting; the default of Managed code. */
class Rc : Managed, ThreadSafe {
    @PublishedApi
    internal val handle: Long = 0L

    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
}

/** Wraps a [Deallocating] allocator; on Mini it reports leaks at runtime with the allocation site. */
class DebugAllocator(private val inner: Deallocating = Heap()) : Deallocating, ThreadSafe {
    @PublishedApi
    internal val handle: Long = nativeDebugNew(if (inner is Heap) inner.handle else 0L)

    var freeCount = 0
        private set

    override fun allocate(size: Long, align: Int): NativePtr? = inner.allocate(size, align)

    override fun free(ptr: NativePtr) {
        freeCount++
        inner.free(ptr)
    }

    override fun free(obj: Any?) {
        if (obj != null) freeCount++
        obj.free()
    }
}
