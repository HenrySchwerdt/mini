#ifndef MINI_CODEGEN_H
#define MINI_CODEGEN_H

#include <stdbool.h>
#include <stddef.h>

#include "mini/checker.h"

/*
 * Supported output formats.
 */
typedef enum {
    MINI_OUTPUT_LLVM_IR,
    MINI_OUTPUT_BITCODE,
    MINI_OUTPUT_OBJECT
} MiniOutputKind;

/*
 * Optimization levels.
 */
typedef enum {
    MINI_OPT_NONE,
    MINI_OPT_LESS,
    MINI_OPT_DEFAULT,
    MINI_OPT_AGGRESSIVE
} MiniOptLevel;

/*
 * Code generation configuration.
 *
 * NULL target_triple selects the host target.
 * NULL cpu selects the generic CPU.
 */
typedef struct {
    MiniOutputKind output_kind;
    MiniOptLevel optimization;

    const char *target_triple;
    const char *cpu;
} MiniCodegenOptions;

/*
 * Opaque backend instance.
 *
 * Internally owns:
 *   LLVMContextRef
 *   LLVMModuleRef
 *   LLVMBuilderRef
 *   LLVMTargetMachineRef
 */
typedef struct MiniCodegen MiniCodegen;

/*
 * Code generation status.
 */
typedef enum {
    MINI_CODEGEN_OK,
    MINI_CODEGEN_INVALID_IR,
    MINI_CODEGEN_TARGET_ERROR,
    MINI_CODEGEN_IO_ERROR,
    MINI_CODEGEN_OUT_OF_MEMORY,
    MINI_CODEGEN_UNSUPPORTED
} MiniCodegenStatus;

/*
 * Result of a code generation operation.
 *
 * message is borrowed and remains valid until
 * the next backend operation or destruction.
 */
typedef struct {
    MiniCodegenStatus status;
    const char *message;
} MiniCodegenResult;

/*
 * Create a new backend.
 *
 * The options are copied.
 * Returns NULL on allocation failure.
 */
MiniCodegen *mini_codegen_create(
    const MiniCodegenOptions *options
);

/*
 * Generate LLVM IR from a checked program.
 *
 * The AST and checked program must remain valid
 * throughout this call.
 *
 * Code generation does not take ownership of them.
 *
 * Each backend instance generates one module.
 */
MiniCodegenResult mini_codegen_generate(
    MiniCodegen *codegen,
    const MiniCheckedProgram *program
);

/*
 * Verify the generated LLVM module.
 *
 * Should be called before emitting output.
 */
MiniCodegenResult mini_codegen_verify(
    MiniCodegen *codegen
);

/*
 * Emit the generated module to a file.
 *
 * Format is selected by options.output_kind.
 */
MiniCodegenResult mini_codegen_emit(
    MiniCodegen *codegen,
    const char *output_path
);

/*
 * Return the generated LLVM IR as a string.
 *
 * The returned memory is owned by the caller.
 * Free using mini_codegen_free_string().
 *
 * Returns NULL on failure.
 */
char *mini_codegen_get_ir(
    MiniCodegen *codegen
);

void mini_codegen_free_string(
    char *string
);

/*
 * Destroy the backend and LLVM resources.
 */
void mini_codegen_destroy(
    MiniCodegen *codegen
);

#endif
