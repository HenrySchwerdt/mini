#ifndef MINI_TYPE_H
#define MINI_TYPE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "mini/ast.h"

typedef struct MiniResolvedType MiniResolvedType;
typedef struct MiniTypeContext MiniTypeContext;

typedef enum {
    MINI_TYPE_ERROR,
    MINI_TYPE_VOID,
    MINI_TYPE_I32,
    MINI_TYPE_U8,
    MINI_TYPE_USIZE,
    MINI_TYPE_BOOL,

    MINI_TYPE_RESOLVED_POINTER,
    MINI_TYPE_RESOLVED_ARRAY,
    MINI_TYPE_RESOLVED_STRUCT,
    MINI_TYPE_RESOLVED_ENUM
} MiniResolvedTypeKind;

struct MiniResolvedType {
    MiniResolvedTypeKind kind;

    union {
        struct {
            const MiniResolvedType *element;
        } pointer;

        struct {
            const MiniResolvedType *element;
            size_t length;
        } array;

        struct {
            const MiniDecl *declaration;
        } nominal;
    } as;
};

/*
 * Owns all resolved types.
 *
 * Resolved type pointers are valid until
 * the context is destroyed.
 */
MiniTypeContext *mini_type_context_create(void);

void mini_type_context_destroy(
    MiniTypeContext *context
);

/*
 * Returns a builtin type owned by the context.
 */
const MiniResolvedType *mini_type_builtin(
    MiniTypeContext *context,
    MiniResolvedTypeKind kind
);

/*
 * Construct canonical pointer and array types.
 *
 * Returns NULL on allocation failure.
 */
const MiniResolvedType *mini_type_pointer(
    MiniTypeContext *context,
    const MiniResolvedType *element
);

const MiniResolvedType *mini_type_array(
    MiniTypeContext *context,
    const MiniResolvedType *element,
    size_t length
);

/*
 * Construct nominal struct/enum types.
 *
 * The declaration must belong to the
 * checked MiniProgram.
 */
const MiniResolvedType *mini_type_nominal(
    MiniTypeContext *context,
    const MiniDecl *declaration
);

/*
 * Structural comparison for pointers/arrays.
 * Nominal comparison for structs/enums.
 */
bool mini_type_equal(
    const MiniResolvedType *a,
    const MiniResolvedType *b
);

bool mini_type_is_integer(
    const MiniResolvedType *type
);

bool mini_type_is_numeric(
    const MiniResolvedType *type
);

#endif
