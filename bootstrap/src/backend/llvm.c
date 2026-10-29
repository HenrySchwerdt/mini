#include "llvm_internal.h"

#include <llvm-c/Analysis.h>
#include <llvm-c/BitWriter.h>
#include <llvm-c/Target.h>
#include <llvm-c/TargetMachine.h>

#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ------------------------------------------------------------
 * Error handling
 * ------------------------------------------------------------ */

static char *copy_string(const char *source) {
    if (!source)
        return NULL;

    size_t length = strlen(source) + 1;

    char *copy = malloc(length);
    if (copy)
        memcpy(copy, source, length);

    return copy;
}

static MiniCodegenResult result(
    MiniCodegen *cg,
    MiniCodegenStatus status,
    const char *message
) {
    free(cg->error_message);
    cg->error_message = NULL;

    if (message)
        cg->error_message = copy_string(message);

    return (MiniCodegenResult){
        .status = status,
        .message = cg->error_message
            ? cg->error_message
            : (status == MINI_CODEGEN_OK
                ? NULL : "code generation failed")
    };
}

static MiniCodegenResult success(MiniCodegen *cg) {
    return result(cg, MINI_CODEGEN_OK, NULL);
}

/* ------------------------------------------------------------
 * Target initialization
 * ------------------------------------------------------------ */

static bool initialize_target(void) {
    if (LLVMInitializeNativeTarget() != 0)
        return false;

    if (LLVMInitializeNativeAsmPrinter() != 0)
        return false;

    return true;
}

static LLVMCodeGenOptLevel optimization_level(
    MiniOptLevel level
) {
    switch (level) {
        case MINI_OPT_NONE:
            return LLVMCodeGenLevelNone;

        case MINI_OPT_LESS:
            return LLVMCodeGenLevelLess;

        case MINI_OPT_DEFAULT:
            return LLVMCodeGenLevelDefault;

        case MINI_OPT_AGGRESSIVE:
            return LLVMCodeGenLevelAggressive;
    }

    return LLVMCodeGenLevelNone;
}

static bool create_target_machine(MiniCodegen *cg) {
    char *host_triple = NULL;

    const char *triple = cg->options.target_triple;

    if (!triple) {
        host_triple = LLVMGetDefaultTargetTriple();
        triple = host_triple;
    }

    if (!triple)
        return false;

    LLVMTargetRef target = NULL;
    char *error_message = NULL;

    if (LLVMGetTargetFromTriple(
        triple,
        &target,
        &error_message
    ) != 0) {
        if (error_message) {
            cg->error_message = copy_string(error_message);
            LLVMDisposeMessage(error_message);
        }

        LLVMDisposeMessage(host_triple);
        return false;
    }

    const char *cpu = cg->options.cpu
        ? cg->options.cpu
        : "generic";

    cg->target_machine = LLVMCreateTargetMachine(
        target,
        triple,
        cpu,
        "",
        optimization_level(cg->options.optimization),
        LLVMRelocDefault,
        LLVMCodeModelDefault
    );

    if (cg->target_machine) {
        LLVMSetTarget(cg->module, triple);

        LLVMTargetDataRef layout =
            LLVMCreateTargetDataLayout(cg->target_machine);

        if (layout) {
            LLVMSetModuleDataLayout(cg->module, layout);
            LLVMDisposeTargetData(layout);
        }
    }

    LLVMDisposeMessage(host_triple);

    return cg->target_machine != NULL;
}

/* ------------------------------------------------------------
 * Backend lifecycle
 * ------------------------------------------------------------ */

MiniCodegen *mini_codegen_create(
    const MiniCodegenOptions *options
) {
    MiniCodegen *cg = calloc(1, sizeof(*cg));
    if (!cg)
        return NULL;

    if (options)
        cg->options = *options;
    else
        cg->options = (MiniCodegenOptions){0};

    if (!initialize_target())
        goto fail;

    cg->context = LLVMContextCreate();
    if (!cg->context)
        goto fail;

    cg->module = LLVMModuleCreateWithNameInContext(
        "mini",
        cg->context
    );

    if (!cg->module)
        goto fail;

    cg->builder = LLVMCreateBuilderInContext(cg->context);
    if (!cg->builder)
        goto fail;

    if (!create_target_machine(cg))
        goto fail;

    return cg;

fail:
    mini_codegen_destroy(cg);
    return NULL;
}

void mini_codegen_destroy(MiniCodegen *cg) {
    if (!cg)
        return;

    if (cg->builder)
        LLVMDisposeBuilder(cg->builder);

    if (cg->module)
        LLVMDisposeModule(cg->module);

    if (cg->target_machine)
        LLVMDisposeTargetMachine(cg->target_machine);

    if (cg->context)
        LLVMContextDispose(cg->context);

    free(cg->error_message);
    free(cg);
}

/* ------------------------------------------------------------
 * IR generation
 * ------------------------------------------------------------ */

MiniCodegenResult mini_codegen_generate(
    MiniCodegen *cg,
    const MiniCheckedProgram *program
) {
    if (!cg)
        return (MiniCodegenResult){
            .status = MINI_CODEGEN_UNSUPPORTED,
            .message = "invalid codegen instance"
        };

    if (!program)
        return result(
            cg,
            MINI_CODEGEN_UNSUPPORTED,
            "checked program is NULL"
        );

    if (cg->generated)
        return result(
            cg,
            MINI_CODEGEN_UNSUPPORTED,
            "module has already been generated"
        );

    /*
     * Do not retry generation in the same backend.
     * A failed generation may leave partial LLVM IR.
     */
    cg->generated = true;

    const MiniProgram *ast = mini_checked_ast(program);

    if (!ast)
        return result(
            cg,
            MINI_CODEGEN_UNSUPPORTED,
            "checked program has no AST"
        );

    /*
     * Declaration pass.
     *
     * TODO: Declare all functions before generating
     * their bodies, to support forward references
     * and recursive function calls.
     */

    for (size_t i = 0; i < ast->count; i++) {
        const MiniDecl *decl = ast->items[i];

        switch (decl->kind) {
            case MINI_DECL_FUNCTION:
                if (!mini_llvm_function(cg, program, decl))
                    return result(
                        cg,
                        MINI_CODEGEN_UNSUPPORTED,
                        "function code generation failed"
                    );
                break;

            case MINI_DECL_STRUCT:
            case MINI_DECL_ENUM:
                /*
                 * No executable code is emitted
                 * directly for these declarations.
                 */
                break;
        }
    }

    return success(cg);
}

/* ------------------------------------------------------------
 * LLVM verification
 * ------------------------------------------------------------ */

MiniCodegenResult mini_codegen_verify(
    MiniCodegen *cg
) {
    if (!cg)
        return (MiniCodegenResult){
            .status = MINI_CODEGEN_UNSUPPORTED,
            .message = "invalid codegen instance"
        };

    if (!cg->generated)
        return result(
            cg,
            MINI_CODEGEN_UNSUPPORTED,
            "no module has been generated"
        );

    char *message = NULL;

    LLVMBool invalid = LLVMVerifyModule(
        cg->module,
        LLVMReturnStatusAction,
        &message
    );

    if (invalid) {
        MiniCodegenResult r = result(
            cg,
            MINI_CODEGEN_INVALID_IR,
            message ? message : "invalid LLVM IR"
        );

        if (message)
            LLVMDisposeMessage(message);

        return r;
    }

    if (message)
        LLVMDisposeMessage(message);

    return success(cg);
}

/* ------------------------------------------------------------
 * LLVM IR string
 * ------------------------------------------------------------ */

char *mini_codegen_get_ir(MiniCodegen *cg) {
    if (!cg || !cg->module || !cg->generated)
        return NULL;

    char *llvm_ir = LLVMPrintModuleToString(cg->module);

    if (!llvm_ir)
        return NULL;

    char *copy = copy_string(llvm_ir);

    LLVMDisposeMessage(llvm_ir);
    return copy;
}

void mini_codegen_free_string(char *string) {
    free(string);
}

/* ------------------------------------------------------------
 * File emission
 * ------------------------------------------------------------ */

MiniCodegenResult mini_codegen_emit(
    MiniCodegen *cg,
    const char *output_path
) {
    if (!cg)
        return (MiniCodegenResult){
            .status = MINI_CODEGEN_UNSUPPORTED,
            .message = "invalid codegen instance"
        };

    if (!output_path)
        return result(
            cg,
            MINI_CODEGEN_IO_ERROR,
            "output path is NULL"
        );

    MiniCodegenResult verified = mini_codegen_verify(cg);

    if (verified.status != MINI_CODEGEN_OK)
        return verified;

    switch (cg->options.output_kind) {
        case MINI_OUTPUT_LLVM_IR: {
            char *message = NULL;

            LLVMBool failed = LLVMPrintModuleToFile(
                cg->module,
                output_path,
                &message
            );

            if (failed) {
                MiniCodegenResult r = result(
                    cg,
                    MINI_CODEGEN_IO_ERROR,
                    message ? message : "cannot write LLVM IR"
                );

                if (message)
                    LLVMDisposeMessage(message);

                return r;
            }

            if (message)
                LLVMDisposeMessage(message);

            break;
        }

        case MINI_OUTPUT_BITCODE:
            if (LLVMWriteBitcodeToFile(
                cg->module,
                output_path
            ) != 0)
                return result(
                    cg,
                    MINI_CODEGEN_IO_ERROR,
                    "cannot write LLVM bitcode"
                );

            break;

        case MINI_OUTPUT_OBJECT: {
            char *message = NULL;

            LLVMBool failed = LLVMTargetMachineEmitToFile(
                cg->target_machine,
                cg->module,
                output_path,
                LLVMObjectFile,
                &message
            );

            if (failed) {
                MiniCodegenResult r = result(
                    cg,
                    MINI_CODEGEN_TARGET_ERROR,
                    message ? message : "object emission failed"
                );

                if (message)
                    LLVMDisposeMessage(message);

                return r;
            }

            if (message)
                LLVMDisposeMessage(message);

            break;
        }

        default:
            return result(
                cg,
                MINI_CODEGEN_UNSUPPORTED,
                "unsupported output format"
            );
    }

    return success(cg);
}
