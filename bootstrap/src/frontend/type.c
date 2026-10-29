#include "mini/type.h"

#include <stdlib.h>

struct MiniTypeContext {
    MiniResolvedType builtins[6];

    MiniResolvedType **allocated;
    size_t count;
    size_t capacity;
};

static bool is_builtin(MiniResolvedTypeKind kind) {
    return kind >= MINI_TYPE_ERROR &&
           kind <= MINI_TYPE_BOOL;
}

static const MiniResolvedType *store_type(
    MiniTypeContext *ctx,
    MiniResolvedType value
) {
    if (ctx->count == ctx->capacity) {
        size_t next_capacity =
            ctx->capacity == 0 ? 16 : ctx->capacity * 2;

        if (next_capacity < ctx->capacity ||
            next_capacity > SIZE_MAX / sizeof(*ctx->allocated))
            return NULL;

        MiniResolvedType **next = realloc(
            ctx->allocated,
            next_capacity * sizeof(*next)
        );

        if (!next)
            return NULL;

        ctx->allocated = next;
        ctx->capacity = next_capacity;
    }

    MiniResolvedType *type = malloc(sizeof(*type));

    if (!type)
        return NULL;

    *type = value;
    ctx->allocated[ctx->count++] = type;

    return type;
}

MiniTypeContext *mini_type_context_create(void) {
    MiniTypeContext *ctx = calloc(1, sizeof(*ctx));

    if (!ctx)
        return NULL;

    for (size_t i = MINI_TYPE_ERROR;
         i <= MINI_TYPE_BOOL;
         i++) {
        ctx->builtins[i].kind = (MiniResolvedTypeKind)i;
    }

    return ctx;
}

void mini_type_context_destroy(MiniTypeContext *ctx) {
    if (!ctx)
        return;

    for (size_t i = 0; i < ctx->count; i++)
        free(ctx->allocated[i]);

    free(ctx->allocated);
    free(ctx);
}

const MiniResolvedType *mini_type_builtin(
    MiniTypeContext *ctx,
    MiniResolvedTypeKind kind
) {
    if (!ctx || !is_builtin(kind))
        return NULL;

    return &ctx->builtins[kind];
}

bool mini_type_equal(
    const MiniResolvedType *a,
    const MiniResolvedType *b
) {
    if (a == b)
        return true;

    if (!a || !b || a->kind != b->kind)
        return false;

    switch (a->kind) {
        case MINI_TYPE_RESOLVED_POINTER:
            return mini_type_equal(
                a->as.pointer.element,
                b->as.pointer.element
            );

        case MINI_TYPE_RESOLVED_ARRAY:
            return a->as.array.length == b->as.array.length &&
                   mini_type_equal(
                       a->as.array.element,
                       b->as.array.element
                   );

        case MINI_TYPE_RESOLVED_STRUCT:
        case MINI_TYPE_RESOLVED_ENUM:
            return a->as.nominal.declaration ==
                   b->as.nominal.declaration;

        default:
            return true;
    }
}

const MiniResolvedType *mini_type_pointer(
    MiniTypeContext *ctx,
    const MiniResolvedType *element
) {
    if (!ctx || !element)
        return NULL;

    for (size_t i = 0; i < ctx->count; i++) {
        MiniResolvedType *type = ctx->allocated[i];

        if (type->kind == MINI_TYPE_RESOLVED_POINTER &&
            mini_type_equal(type->as.pointer.element, element))
            return type;
    }

    MiniResolvedType value = {
        .kind = MINI_TYPE_RESOLVED_POINTER,
        .as.pointer.element = element
    };

    return store_type(ctx, value);
}

const MiniResolvedType *mini_type_array(
    MiniTypeContext *ctx,
    const MiniResolvedType *element,
    size_t length
) {
    if (!ctx || !element)
        return NULL;

    for (size_t i = 0; i < ctx->count; i++) {
        MiniResolvedType *type = ctx->allocated[i];

        if (type->kind == MINI_TYPE_RESOLVED_ARRAY &&
            type->as.array.length == length &&
            mini_type_equal(type->as.array.element, element))
            return type;
    }

    MiniResolvedType value = {
        .kind = MINI_TYPE_RESOLVED_ARRAY,
        .as.array = {
            .element = element,
            .length = length
        }
    };

    return store_type(ctx, value);
}

const MiniResolvedType *mini_type_nominal(
    MiniTypeContext *ctx,
    const MiniDecl *decl
) {
    if (!ctx || !decl)
        return NULL;

    MiniResolvedTypeKind kind;

    switch (decl->kind) {
        case MINI_DECL_STRUCT:
            kind = MINI_TYPE_RESOLVED_STRUCT;
            break;

        case MINI_DECL_ENUM:
            kind = MINI_TYPE_RESOLVED_ENUM;
            break;

        default:
            return NULL;
    }

    for (size_t i = 0; i < ctx->count; i++) {
        MiniResolvedType *type = ctx->allocated[i];

        if (type->kind == kind &&
            type->as.nominal.declaration == decl)
            return type;
    }

    MiniResolvedType value = {
        .kind = kind,
        .as.nominal.declaration = decl
    };

    return store_type(ctx, value);
}

bool mini_type_is_integer(const MiniResolvedType *type) {
    if (!type)
        return false;

    return type->kind == MINI_TYPE_I32 ||
           type->kind == MINI_TYPE_U8 ||
           type->kind == MINI_TYPE_USIZE;
}

bool mini_type_is_numeric(const MiniResolvedType *type) {
    return mini_type_is_integer(type);
}
