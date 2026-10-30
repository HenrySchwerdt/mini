package mini.backend.llvm

import mini.backend.ANY_CNAME
import mini.backend.EmitError
import mini.backend.MirClass
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TByte
import mini.backend.TChar
import mini.backend.TFloat
import mini.backend.TPtr
import mini.backend.TShort
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.isRef

/**
 * What the LLVM backend needs to know about the target. The emitted IR does not rely on a data layout: object
 * layouts are computed here (the same rules as the C compiler: natural alignment, a subclass embeds its superclass
 * struct, whose size includes its tail padding) and every field access is a byte-offset `getelementptr`.
 *
 * [triple] / [dataLayout] are written into the module when non-null; with null clang substitutes its own target.
 *
 * [pendingAccessor]: generated code reaches the thread-local `mk_pending_exception` through the runtime function
 * `mk_pending_slot()` (returns its address) instead of a direct `thread_local` global reference. For targets whose
 * loader cannot resolve TLS relocations of generated code (the in-process ORC JIT); the optimizer treats the call as
 * pure, so it costs one call per function that tests for exceptions.
 */
class LlvmTarget(val ptrBytes: Int, val triple: String?, val dataLayout: String?, val pendingAccessor: Boolean = false) {
    /** The LLVM integer type of `size_t`. */
    val sizeT: String get() = if (ptrBytes == 8) "i64" else "i32"

    companion object {
        /** The host of the clang that compiles the IR (any 64-bit target with natural alignment). */
        val HOST = LlvmTarget(8, null, null)
        val WASM32 = LlvmTarget(4, "wasm32-unknown-wasi", null)

    }
}

/** Size, alignment and field offsets of one class struct. */
class ClassLayout(val size: Int, val align: Int, val offsets: HashMap<String, Int>)

/** Object layouts of a module, memoised per class. */
internal class Layouts(private val classes: Map<String, MirClass>, private val target: LlvmTarget) {
    private val memo = HashMap<String, ClassLayout>()

    /** `struct MkObject`: type pointer, refcount, flags. */
    val headerSize: Int get() = target.ptrBytes + 8
    /** Offset of `refcount` in the header (the flags follow 4 bytes later). */
    val refcountOffset: Int get() = target.ptrBytes

    fun alignUp(n: Int, a: Int): Int = (n + a - 1) / a * a

    fun sizeOf(t: MirType): Int = when {
        t === TInt -> 4
        t === TLong || t === TDouble -> 8
        t === TBoolean -> 1
        t === TChar || t === TShort -> 2
        t === TByte -> 1
        t === TFloat -> 4
        t is TPtr -> target.ptrBytes
        t.isRef -> target.ptrBytes
        else -> throw EmitError("no size for $t")
    }

    fun alignOf(t: MirType): Int = if (t === TLong || t === TDouble) 8 else sizeOf(t)

    fun of(cName: String): ClassLayout {
        val known = memo[cName]
        if (known != null) return known
        val c = classes[cName] ?: throw EmitError("unknown class $cName")
        val base: ClassLayout? = if (c.superCName != null) of(c.superCName) else null
        var cur: Int
        var align: Int
        val offsets = HashMap<String, Int>()
        if (base != null) {
            cur = base.size
            align = base.align
        } else {
            cur = headerSize
            align = target.ptrBytes
            if (c.external) {
                // the built-in exception classes: MkThrowable { header, message, cause }
                offsets["message"] = cur
                cur += target.ptrBytes
                offsets["cause"] = cur
                cur += target.ptrBytes
            }
        }
        if (!c.external) {
            for (f in c.fields) {
                val a = alignOf(f.type)
                cur = alignUp(cur, a)
                offsets[f.name] = cur
                cur += sizeOf(f.type)
                if (a > align) align = a
            }
        }
        val l = ClassLayout(alignUp(cur, align), align, offsets)
        memo[cName] = l
        return l
    }

    /** Byte offset of field [field] declared by class [owner]. */
    fun fieldOffset(owner: String, field: String): Int {
        if (owner == ANY_CNAME) throw EmitError("field $field of kotlin.Any")
        return of(owner).offsets[field] ?: throw EmitError("unknown field $owner.$field")
    }
}
