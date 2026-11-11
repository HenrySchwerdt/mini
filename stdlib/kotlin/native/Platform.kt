/*
 * Kotlin/Native `kotlin.native.Platform` (imported by default there): the memory model of mini is the relaxed, thread-safe one
 * (atomic reference counts once a thread started, docs/threads.md), which K/N calls EXPERIMENTAL.
 */
package kotlin.native

public enum class MemoryModel { STRICT, RELAXED, EXPERIMENTAL }

public object Platform {
    public val memoryModel: MemoryModel get() = MemoryModel.EXPERIMENTAL
    public val isLittleEndian: Boolean get() = true
    public val canAccessUnaligned: Boolean get() = true
    public val isDebugBinary: Boolean get() = false
}
