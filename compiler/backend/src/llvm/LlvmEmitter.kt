package mini.backend.llvm

import mini.backend.MirModule
import mini.backend.MirFunction

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
        /** Classes described by this module but defined in another object. Their layouts remain available to lowering. */
        externalClassNames: Set<String> = emptySet(),
        /** Classes defined by this object whose type information is a cross-object symbol. */
        exportedClassNames: Set<String> = emptySet(),
        /** Function signatures imported from other modules, used for calls and cross-module RC conventions. */
        externalFunctionSignatures: List<MirFunction> = emptyList(),
        /** Effective owned-parameter ABI for imported functions (including inferred constructor ownership). */
        externalFunctionOwnedMasks: Map<String, BooleanArray> = emptyMap(),
        /** Imported functions whose effective return ABI is borrowed after dispatch-group conformance. */
        externalBorrowedFunctions: Set<String> = emptySet(),
        /** Preserve the historical all-classes external mode used by the ORC session. */
        externalClasses: Boolean = false,
        /** Permit calls to functions whose definitions are in another object. */
        externalFunctions: Boolean = false,
    ): String {
        val builder = TextIrBuilder()
        LlvmLowering.lower(
            module, builder, rcOpt, strict, conventions, stats, target,
            externalClasses = externalClasses, externalFunctions = externalFunctions,
            externalFunctionSignatures = externalFunctionSignatures,
            externalFunctionOwnedMasks = externalFunctionOwnedMasks,
            externalBorrowedFunctions = externalBorrowedFunctions,
            externalClassNames = externalClassNames,
            exportedClassNames = exportedClassNames,
        )
        return builder.finish()
    }
}
