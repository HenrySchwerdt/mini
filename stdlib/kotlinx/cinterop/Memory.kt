/*
 * C interop: allocating C memory (docs/plan-kotlin.md section 7, compiler/README.md "C interop").
 *
 *  - `nativeHeap`: malloc/free; everything allocated must be freed (`nativeHeap.free(x)`), the memory checker
 *    treats it like any other `Deallocating` allocator.
 *  - `memScoped { }`: a `Scoped` allocator (docs/memory-model.md 2.4) that frees everything it handed out when the block
 *    ends. A pointer allocated in it must not leave the block (M0105).
 *
 * Ordinary Kotlin on top of libc (`calloc`/`free` are called through `external fun`) and the intrinsics.
 */
package kotlinx.cinterop

import kotlin.native.CName

@CName("calloc")
internal external fun libcCalloc(count: Long, size: Long): NativePtr?

@CName("free")
internal external fun libcFree(ptr: NativePtr?)

/** Memory that can hand out C variables (`alloc<IntVar>()`, `allocArray<ByteVar>(n)`); the memory is zeroed. */
public interface NativePlacement {
    public fun alloc(size: Long, align: Int): NativePointed
}

public fun NativePlacement.alloc(size: Int, align: Int): NativePointed = alloc(size.toLong(), align)

public interface NativeFreeablePlacement : NativePlacement {
    public fun free(mem: NativePtr)
}

/** The C heap. */
public object nativeHeap : NativeFreeablePlacement {
    override fun alloc(size: Long, align: Int): NativePointed {
        val p = libcCalloc(1L, if (size < 1L) 1L else size)
        if (p == null) throw IllegalStateException("out of memory")
        return interpretPointed<CPointed>(p)
    }

    override fun free(mem: NativePtr) {
        libcFree(mem)
    }
}

public fun NativeFreeablePlacement.free(pointer: CPointer<*>) {
    free(pointer.rawValue)
}

public fun NativeFreeablePlacement.free(pointed: NativePointed) {
    free(pointed.rawPtr)
}

public inline fun <reified T : CVariable> NativePlacement.alloc(): T =
    interpretPointed<T>(alloc(sizeOf<T>(), alignOf<T>().toInt()).rawPtr)

public inline fun <reified T : CVariable> NativePlacement.alloc(initialize: T.() -> Unit): T {
    val v = alloc<T>()
    v.initialize()
    return v
}

public inline fun <reified T : CVariable> NativePlacement.allocArray(length: Long): CArrayPointer<T> =
    mkCPointer<T>(alloc(sizeOf<T>() * length, alignOf<T>().toInt()).rawPtr)

public inline fun <reified T : CVariable> NativePlacement.allocArray(length: Int): CArrayPointer<T> = allocArray<T>(length.toLong())

public fun <T : CPointed> NativePlacement.allocPointerTo(): CPointerVar<T> = alloc<CPointerVar<T>>()

/** A scope that frees what it allocated when it ends. */
public abstract class AutofreeScope : NativePlacement

/** The allocator of `memScoped`. */
public class MemScope : AutofreeScope(), mini.memory.Scoped {
    private var head: NativePtr? = null

    /** Each block starts with a 16 byte header (the previous block); the payload keeps malloc's 16 byte alignment. */
    override fun alloc(size: Long, align: Int): NativePointed {
        if (align > 16) throw IllegalArgumentException("alignment above 16 is not supported")
        val raw = libcCalloc(1L, (if (size < 1L) 1L else size) + 16L)
        if (raw == null) throw IllegalStateException("out of memory")
        mkStorePtr(raw, 0L, head)
        head = raw
        return interpretPointed<CPointed>(raw + 16L)
    }

    override fun allocate(size: Long, align: Int): mini.memory.NativePtr? =
        mini.memory.NativePtr(alloc(size, align).rawPtr.toLong())

    /** Frees every block; the scope can be used again. */
    public fun clearAll() {
        var p = head
        while (p != null) {
            val next = mkLoadPtr(p, 0L)
            libcFree(p)
            p = next
        }
        head = null
    }

    override fun close() {
        clearAll()
    }

    /** The memory of a `String.cstr` / array value, placed in this scope. */
    public val <T : CVariable> CValues<T>.ptr: CPointer<T>
        get() = this.getPointer(this@MemScope)
}

/**
 * Runs [block] with a [MemScope]; everything allocated in it is freed when the block ends (also when it throws).
 * Pointers into the scope must not outlive it.
 */
public inline fun <R> memScoped(block: MemScope.() -> R): R {
    val scope = MemScope()
    try {
        return scope.block()
    } finally {
        scope.clearAll()
    }
}
