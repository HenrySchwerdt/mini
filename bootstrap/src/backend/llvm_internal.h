
#ifndef MINI_LLVM_INTERNAL_H
#define MINI_LLVM_INTERNAL_H

#include <llvm-c/Core.h>
#include <llvm-c/Target.h>
#include <llvm-c/TargetMachine.h>

#include "mini/codegen.h"
#include "mini/ast.h"
#include "mini/type.h"

/*
 * Private LLVM backend state.
 */
struct MiniCodegen {
    MiniCodegenOptions options;

    LLVMContextRef context;
    LLVMModuleRef module;
    LLVMBuilderRef builder;

    LLVMTargetMachineRef target_machine;

    char *error_message;
    bool generated;
};

/*
 * Map a Mini type to an LLVM type.
 */
LLVMTypeRef mini_llvm_type(
    MiniCodegen *codegen,
    const MiniResolvedType *type
);

/*
 * Generate an expression.
 */
LLVMValueRef mini_llvm_expr(
    MiniCodegen *codegen,
    const MiniCheckedProgram *program,
    const MiniExpr *expr
);

/*
 * Generate a statement.
 */
bool mini_llvm_stmt(
    MiniCodegen *codegen,
    const MiniCheckedProgram *program,
    const MiniStmt *stmt
);

/*
 * Generate function declarations and bodies.
 */
bool mini_llvm_function(
    MiniCodegen *codegen,
    const MiniCheckedProgram *program,
    const MiniDecl *function
);

#endif
