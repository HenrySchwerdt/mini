package mini.backend

/*
 * C interop (docs/plan-kotlin.md section 7): the data layout rules both frontends share, so that `sizeOf<T>()`, the
 * offsets of struct fields and the C types of external functions come out the same whichever frontend ran.
 *
 * Pointers are 8 bytes (LP64: Linux, macOS, 64-bit Windows with clang); the 32-bit LLVM target does not support C interop.
 * Pure Kotlin (no JVM APIs), like the rest of the backend.
 */

/** One field of a `@CStruct` class after its type was resolved: [size] / [align] in bytes. */
class CFieldSpec(val name: String, val size: Long, val align: Int)

/** The layout of a C struct: its size and alignment and the byte offset of every field (same order as the input). */
class CStructLayout(val size: Long, val align: Int, val offsets: List<Long>)

object CInterop {
    const val POINTER_SIZE = 8L

    /** Size in bytes of the C type a scalar MIR type stands for, or -1 for a type that has no C representation here. */
    fun scalarSize(t: MirType): Long = when (t) {
        TByte, TBoolean -> 1L
        TShort, TChar -> 2L
        TInt, TFloat -> 4L
        TLong, TDouble -> 8L
        is TPtr -> POINTER_SIZE
        else -> -1L
    }

    /** True for the MIR types an external function may have as parameter or result (and `Unit` as result). */
    fun isCType(t: MirType, asResult: Boolean): Boolean =
        scalarSize(t) > 0L || (asResult && (t === TUnit || t === TNothing))

    /**
     * The layout of a struct with the given [fields], by the C rules: every field at the next multiple of its alignment,
     * the size rounded up to the struct's alignment (the largest field alignment). [sizeOverride] / [alignOverride]
     * (when not negative) replace the computed values (`@CStruct(name, size = ..., align = ...)`).
     */
    fun layout(fields: List<CFieldSpec>, sizeOverride: Long, alignOverride: Int): CStructLayout {
        var offset = 0L
        var align = 1
        val offsets = ArrayList<Long>()
        for (f in fields) {
            val a = if (f.align < 1) 1 else f.align
            if (a > align) align = a
            offset = alignUp(offset, a.toLong())
            offsets.add(offset)
            offset += f.size
        }
        if (alignOverride > 0) align = alignOverride
        val size = if (sizeOverride >= 0L) sizeOverride else alignUp(offset, align.toLong())
        return CStructLayout(size, align, offsets)
    }

    fun alignUp(n: Long, a: Long): Long = if (a <= 1L) n else (n + a - 1L) / a * a
}
