package mini.memory

/** malloc/free. Stateless: creating a `Heap()` is free and carries no obligation. */
class Heap : Deallocating, ThreadSafe {
    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
    override fun free(ptr: NativePtr) {}
}

/** Region allocator. It is a resource itself: it must be closed (`use {}`), see M0102. */
class Arena : Scoped {
    private var used = 0L
    val bytesUsed: Long get() = used

    override fun allocate(size: Long, align: Int): NativePtr? {
        used += size
        return NativePtr(used)
    }

    override fun close() {
        used = 0
    }
}

/** Fixed-capacity pool of objects of one type. Not [ThreadSafe]. A resource like [Arena]: close it. */
class Pool<T : Any>(val capacity: Int) : Deallocating, AutoCloseable {
    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
    override fun free(ptr: NativePtr) {}
    override fun close() {}
}

/** Reference counting; the default of Managed code. */
class Rc : Managed, ThreadSafe {
    override fun allocate(size: Long, align: Int): NativePtr? = NativePtr(size)
}

/** Wraps a [Deallocating] allocator; on Mini it reports leaks at runtime with the allocation site. */
class DebugAllocator(private val inner: Deallocating = Heap()) : Deallocating, ThreadSafe {
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
