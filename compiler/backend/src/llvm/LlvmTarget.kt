package mini.backend.llvm

import mini.backend.ClassLayout
import mini.backend.MirClass
import mini.backend.ObjectLayouts

/**
 * What the LLVM backend needs to know about the target. The emitted IR does not rely on a data layout: object
 * layouts come from the shared [ObjectLayouts] (the same function the C emitter uses to order its structs; natural
 * alignment, fields sorted to remove padding, a subclass fills its superclass's tail padding) and every field access is a
 * byte-offset `getelementptr`.
 *
 * [triple] / [dataLayout] are written into the module when non-null; with null clang substitutes its own target.
 *
 * [pendingAccessor]: generated code reaches the thread-local `mk_pending_exception` through the runtime function
 * `mk_pending_slot()` (returns its address) instead of a direct `thread_local` global reference. For targets whose
 * loader cannot resolve TLS relocations of generated code (the in-process ORC JIT); the optimizer treats the call as
 * pure, so it costs one call per function that tests for exceptions.
 */
class LlvmTarget(val ptrBytes: Int, val triple: String?, val dataLayout: String?, val pendingAccessor: Boolean = false, val wasmLib: Boolean = false) {
    /** The LLVM integer type of `size_t`. */
    val sizeT: String get() = if (ptrBytes == 8) "i64" else "i32"

    companion object {
        /** The host of the clang that compiles the IR (any 64-bit target with natural alignment). */
        val HOST = LlvmTarget(8, null, null)
        val WASM32 = LlvmTarget(4, "wasm32-unknown-wasi", null)

        /** A wasm32 library module (no entry point): it exports its `@WasmExport` functions and `mini_init` (WasmLib.kt). */
        val WASM32_LIB = LlvmTarget(4, "wasm32-unknown-wasi", null, false, true)

    }
}

/** Object layouts of a module: the shared [ObjectLayouts] for this target's pointer size. */
internal class Layouts(classes: Map<String, MirClass>, private val target: LlvmTarget) {
    private val shared = ObjectLayouts(classes, target.ptrBytes)

    /** `struct MkObject`: type pointer, refcount, flags. */
    val headerSize: Int get() = shared.headerSize
    /** Offset of `refcount` in the header (the flags follow 4 bytes later). */
    val refcountOffset: Int get() = target.ptrBytes

    fun of(cName: String): ClassLayout = shared.of(cName)

    /** Byte offset of field [field] declared by class [owner]. */
    fun fieldOffset(owner: String, field: String): Int = shared.fieldOffset(owner, field)
}
