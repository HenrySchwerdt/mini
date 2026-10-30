package mini.backend.llvm

import mini.backend.MirModule

/**
 * MIR to textual LLVM IR (`.ll`), the alternative to [mini.backend.CEmitter] (docs/plan-kotlin.md section 10g, phase 1).
 *
 * The lowering itself is [LlvmLowering], written once against the small [IrBuilder] interface; this is the text
 * implementation of it ([TextIrBuilder]). The in-process one (the LLVM C API, no text) is `ApiIrBuilder` in compiler/llvm-c.
 *
 * Pure Kotlin like the rest of the backend (self-hosting rule, section 10a).
 */
object LlvmEmitter {
    /**
     * [rcOpt], [strict] and [conventions] mean what they mean for [mini.backend.CEmitter.emit] (RcOpt, Strict code generation
     * with tracked values, borrowed results / owned parameters of the stdlib hot paths); [stats] is the MK_STATS build.
     */
    fun emit(
        module: MirModule,
        rcOpt: Boolean = true,
        strict: Boolean = true,
        conventions: Boolean = true,
        stats: Boolean = false,
        target: LlvmTarget = LlvmTarget.HOST,
    ): String {
        val builder = TextIrBuilder()
        LlvmLowering.lower(module, builder, rcOpt, strict, conventions, stats, target)
        return builder.finish()
    }
}
