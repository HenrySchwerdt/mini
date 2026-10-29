#ifndef MINI_CHECKER_H
#define MINI_CHECKER_H

#include <stddef.h>

#include "mini/ast.h"
#include "mini/type.h"

/*
 * Opaque types.
 *
 * Implementation details are private to checker.c.
 */
typedef struct MiniChecker MiniChecker;
typedef struct MiniCheckedProgram MiniCheckedProgram;

/*
 * Semantic analysis result.
 */
typedef enum {
    MINI_CHECK_OK,
    MINI_CHECK_TYPE_ERROR,
    MINI_CHECK_OUT_OF_MEMORY
} MiniCheckStatus;

typedef struct {
    MiniCheckStatus status;

    /* Byte offsets into the source buffer. */
    size_t error_start;
    size_t error_end;

    /*
     * Static error message.
     * NULL when status == MINI_CHECK_OK.
     */
    const char *message;
} MiniCheckResult;

/*
 * Create and destroy a checker.
 */
MiniChecker *mini_checker_create(void);

void mini_checker_destroy(
    MiniChecker *checker
);

/*
 * Perform semantic analysis.
 *
 * On success:
 *   - Returns MINI_CHECK_OK.
 *   - *out_program contains an owned checked program.
 *
 * On failure:
 *   - Returns an error status.
 *   - *out_program is NULL.
 *
 * The original AST and source buffer must remain
 * valid while MiniCheckedProgram is in use.
 */
MiniCheckResult mini_checker_check(
    MiniChecker *checker,
    const MiniProgram *program,
    const char *source,
    size_t source_length,
    MiniCheckedProgram **out_program
);

/*
 * Query the resolved type of an expression.
 *
 * Returns NULL if no type information exists.
 * The returned pointer is borrowed.
 */
const MiniResolvedType *mini_checked_expr_type(
    const MiniCheckedProgram *program,
    const MiniExpr *expression
);

/*
 * Query a function's resolved return type.
 *
 * Returns NULL if the declaration is not
 * a function in the checked program.
 */
const MiniResolvedType *mini_checked_return_type(
    const MiniCheckedProgram *program,
    const MiniDecl *function
);

/*
 * Access the original AST.
 *
 * Borrowed reference.
 * Does not transfer ownership.
 */
const MiniProgram *mini_checked_ast(
    const MiniCheckedProgram *program
);

/*
 * Free all semantic analysis data.
 *
 * Does not free the original AST or source.
 */
void mini_checked_program_destroy(
    MiniCheckedProgram *program
);

#endif
