#include "mini/checker.h"
#include "mini/symbol.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const MiniExpr *expr;
    const MiniResolvedType *type;
} ExprTypeEntry;

struct MiniCheckedProgram {
    const MiniProgram *ast;
    MiniTypeContext *types;

    ExprTypeEntry *expr_types;
    size_t expr_count;
    size_t expr_capacity;

    const MiniResolvedType **return_types;
};

struct MiniChecker {
    int unused;
};

typedef struct {
    MiniCheckedProgram *checked;
    MiniScope *globals;
    MiniScope *scope;

    const char *source;
    size_t source_length;

    const MiniResolvedType *return_type;

    MiniCheckResult result;
} CheckContext;

static MiniCheckResult ok(void) {
    return (MiniCheckResult){
        .status = MINI_CHECK_OK
    };
}

static void fail(
    CheckContext *c,
    size_t start,
    size_t end,
    const char *message
) {
    if (c->result.status != MINI_CHECK_OK)
        return;

    c->result = (MiniCheckResult){
        .status = MINI_CHECK_TYPE_ERROR,
        .error_start = start,
        .error_end = end,
        .message = message
    };
}

static void oom(CheckContext *c) {
    if (c->result.status != MINI_CHECK_OK)
        return;

    c->result.status = MINI_CHECK_OUT_OF_MEMORY;
    c->result.message = "out of memory";
}

static bool valid(CheckContext *c) {
    return c->result.status == MINI_CHECK_OK;
}

static bool name_eq(
    MiniSymbolName a,
    MiniSymbolName b
) {
    return a.length == b.length &&
           (a.length == 0 ||
            memcmp(a.data, b.data, a.length) == 0);
}

static bool token_eq(CheckContext *c, Token token, Token other) {
    if (token.start > c->source_length ||
        token.length > c->source_length - token.start ||
        other.start > c->source_length ||
        other.length > c->source_length - other.start)
        return false;

    MiniSymbolName a = {
        c->source + token.start, token.length
    };
    MiniSymbolName b = {
        c->source + other.start, other.length
    };
    return name_eq(a, b);
}

static bool token_text(
    CheckContext *c,
    Token token,
    const char *text
) {
    size_t length = strlen(text);

    return token.start <= c->source_length &&
           token.length <= c->source_length - token.start &&
           token.length == length &&
           memcmp(c->source + token.start, text, length) == 0;
}

static MiniSymbolName token_name(CheckContext *c, Token token) {
    if (token.start > c->source_length ||
        token.length > c->source_length - token.start) {
        fail(c, token.start, token.start,
             "invalid token span");
        return (MiniSymbolName){0};
    }

    return (MiniSymbolName){
        .data = c->source + token.start,
        .length = token.length
    };
}

static const MiniResolvedType *builtin(
    CheckContext *c,
    MiniResolvedTypeKind kind
) {
    return mini_type_builtin(c->checked->types, kind);
}

static bool same(
    const MiniResolvedType *a,
    const MiniResolvedType *b
) {
    return mini_type_equal(a, b);
}

static bool remember_expr(
    CheckContext *c,
    const MiniExpr *expr,
    const MiniResolvedType *type
) {
    MiniCheckedProgram *p = c->checked;

    if (p->expr_count == p->expr_capacity) {
        size_t cap = p->expr_capacity
            ? p->expr_capacity * 2 : 32;

        if (cap < p->expr_capacity ||
            cap > SIZE_MAX / sizeof(*p->expr_types)) {
            oom(c);
            return false;
        }

        ExprTypeEntry *next = realloc(
            p->expr_types,
            cap * sizeof(*next)
        );

        if (!next) {
            oom(c);
            return false;
        }

        p->expr_types = next;
        p->expr_capacity = cap;
    }

    p->expr_types[p->expr_count++] =
        (ExprTypeEntry){expr, type};

    return true;
}

static bool parse_size(
    CheckContext *c,
    Token token,
    size_t *out
) {
    if (token.kind != TOKEN_INTEGER ||
        token.length == 0 ||
        token.start > c->source_length ||
        token.length > c->source_length - token.start)
        return false;

    size_t value = 0;

    for (size_t i = 0; i < token.length; i++) {
        char ch = c->source[token.start + i];

        if (ch < '0' || ch > '9')
            return false;

        size_t digit = (size_t)(ch - '0');

        if (value > (SIZE_MAX - digit) / 10)
            return false;

        value = value * 10 + digit;
    }

    *out = value;
    return true;
}

static const MiniResolvedType *resolve_type(
    CheckContext *c,
    const MiniType *type
) {
    if (!type || !valid(c))
        return NULL;

    switch (type->kind) {
        case MINI_TYPE_FUNCTION:
            fail(c, type->start, type->end, "use mini_compile_project for function type analysis");
            return NULL;
        case MINI_TYPE_NAMED: {
            Token name = type->as.name;

            if (token_text(c, name, "i32"))
                return builtin(c, MINI_TYPE_I32);

            if (token_text(c, name, "u8"))
                return builtin(c, MINI_TYPE_U8);

            if (token_text(c, name, "usize"))
                return builtin(c, MINI_TYPE_USIZE);

            if (token_text(c, name, "bool"))
                return builtin(c, MINI_TYPE_BOOL);

            MiniSymbolName symbol_name = token_name(c, name);
            if (!valid(c))
                return NULL;

            const MiniSymbol *symbol =
                mini_scope_lookup(c->globals, symbol_name);

            if (!symbol || symbol->kind != MINI_SYMBOL_TYPE) {
                fail(c, type->start, type->end, "unknown type");
                return NULL;
            }

            return symbol->type;
        }

        case MINI_TYPE_POINTER: {
            const MiniResolvedType *element =
                resolve_type(c, type->as.pointer.element);

            if (!element)
                return NULL;

            const MiniResolvedType *result =
                mini_type_pointer(c->checked->types, element);

            if (!result)
                oom(c);

            return result;
        }

        case MINI_TYPE_ARRAY: {
            const MiniResolvedType *element =
                resolve_type(c, type->as.array.element);

            if (!element)
                return NULL;

            size_t length;

            if (!parse_size(c, type->as.array.length, &length)) {
                fail(c, type->start, type->end,
                     "invalid array length");
                return NULL;
            }

            const MiniResolvedType *result =
                mini_type_array(c->checked->types, element, length);

            if (!result)
                oom(c);

            return result;
        }
    }

    fail(c, type->start, type->end, "invalid type");
    return NULL;
}

static const MiniResolvedType *function_return(
    CheckContext *c,
    const MiniDecl *decl
) {
    const MiniProgram *program = c->checked->ast;

    for (size_t i = 0; i < program->count; i++)
        if (program->items[i] == decl)
            return c->checked->return_types[i];

    return NULL;
}

static const MiniResolvedType *check_expr(
    CheckContext *c,
    const MiniExpr *expr
);

static const MiniResolvedType *check_member(
    CheckContext *c,
    const MiniExpr *expr
) {
    const MiniExpr *target = expr->as.member.target;
    Token member = expr->as.member.name;

    // Enum.Variant
    if (target->kind == MINI_EXPR_IDENTIFIER) {
        MiniSymbolName name =
            token_name(c, target->as.identifier);

        const MiniSymbol *symbol =
            mini_scope_lookup(c->globals, name);

        if (symbol && symbol->kind == MINI_SYMBOL_TYPE &&
            symbol->type->kind == MINI_TYPE_RESOLVED_ENUM) {
            const MiniDecl *decl = symbol->declaration;

            for (size_t i = 0;
                 i < decl->as.enum_decl.variant_count; i++) {
                if (token_eq(c, member,
                             decl->as.enum_decl.variants[i])) {
                    return symbol->type;
                }
            }

            fail(c, expr->start, expr->end,
                 "unknown enum variant");
            return NULL;
        }
    }

    const MiniResolvedType *target_type = check_expr(c, target);
    if (!target_type)
        return NULL;

    if (target_type->kind != MINI_TYPE_RESOLVED_STRUCT) {
        fail(c, expr->start, expr->end,
             "member access requires a struct");
        return NULL;
    }

    const MiniDecl *decl = target_type->as.nominal.declaration;

    for (size_t i = 0;
         i < decl->as.struct_decl.field_count; i++) {
        const MiniParameter *field =
            &decl->as.struct_decl.fields[i];

        if (token_eq(c, member, field->name))
            return resolve_type(c, field->type);
    }

    fail(c, expr->start, expr->end, "unknown struct field");
    return NULL;
}

static const MiniResolvedType *check_call(
    CheckContext *c,
    const MiniExpr *expr
) {
    const MiniExpr *callee = expr->as.call.callee;

    if (callee->kind != MINI_EXPR_IDENTIFIER) {
        fail(c, expr->start, expr->end,
             "only named function calls are supported");
        return NULL;
    }

    MiniSymbolName name = token_name(c, callee->as.identifier);
    const MiniSymbol *symbol = mini_scope_lookup(c->scope, name);

    if (!symbol || symbol->kind != MINI_SYMBOL_FUNCTION) {
        fail(c, expr->start, expr->end, "unknown function");
        return NULL;
    }

    const MiniDecl *decl = symbol->declaration;
    size_t count = decl->as.function.param_count;

    if (expr->as.call.args.count != count) {
        fail(c, expr->start, expr->end,
             "incorrect argument count");
        return NULL;
    }

    for (size_t i = 0; i < count; i++) {
        const MiniResolvedType *expected =
            resolve_type(c, decl->as.function.params[i].type);

        const MiniResolvedType *actual =
            check_expr(c, expr->as.call.args.items[i]);

        if (!expected || !actual)
            return NULL;

        if (!same(expected, actual)) {
            fail(c, expr->start, expr->end,
                 "function argument type mismatch");
            return NULL;
        }
    }

    return function_return(c, decl);
}

static const MiniResolvedType *check_struct_literal(
    CheckContext *c,
    const MiniExpr *expr
) {
    MiniSymbolName name =
        token_name(c, expr->as.struct_literal.type_name);

    const MiniSymbol *symbol =
        mini_scope_lookup(c->globals, name);

    if (!symbol || symbol->kind != MINI_SYMBOL_TYPE ||
        symbol->type->kind != MINI_TYPE_RESOLVED_STRUCT) {
        fail(c, expr->start, expr->end,
             "unknown struct type");
        return NULL;
    }

    const MiniDecl *decl = symbol->declaration;
    size_t expected_count = decl->as.struct_decl.field_count;

    if (expr->as.struct_literal.field_count != expected_count) {
        fail(c, expr->start, expr->end,
             "incorrect number of struct fields");
        return NULL;
    }

    for (size_t i = 0; i < expected_count; i++) {
        const MiniParameter *field =
            &decl->as.struct_decl.fields[i];

        const MiniFieldInit *found = NULL;

        for (size_t j = 0;
             j < expr->as.struct_literal.field_count; j++) {
            const MiniFieldInit *candidate =
                &expr->as.struct_literal.fields[j];

            if (token_eq(c, candidate->name, field->name)) {
                if (found) {
                    fail(c, expr->start, expr->end,
                         "duplicate struct field");
                    return NULL;
                }
                found = candidate;
            }
        }

        if (!found) {
            fail(c, expr->start, expr->end,
                 "missing struct field");
            return NULL;
        }

        const MiniResolvedType *expected =
            resolve_type(c, field->type);

        const MiniResolvedType *actual =
            check_expr(c, found->value);

        if (!expected || !actual)
            return NULL;

        if (!same(expected, actual)) {
            fail(c, found->name.start,
                 found->name.start + found->name.length,
                 "struct field type mismatch");
            return NULL;
        }
    }

    return symbol->type;
}

static const MiniResolvedType *check_match(
    CheckContext *c,
    const MiniExpr *expr
) {
    const MiniResolvedType *subject =
        check_expr(c, expr->as.match.subject);

    if (!subject)
        return NULL;

    const MiniResolvedType *result = NULL;
    bool wildcard = false;

    for (size_t i = 0; i < expr->as.match.arm_count; i++) {
        const MiniMatchArm *arm = &expr->as.match.arms[i];
        Token pattern = arm->pattern;

        if (wildcard) {
            fail(c, pattern.start,
                 pattern.start + pattern.length,
                 "unreachable match arm");
            return NULL;
        }

        if (pattern.kind == TOKEN_UNDERSCORE) {
            wildcard = true;
        } else if (pattern.kind == TOKEN_INTEGER) {
            if (!mini_type_is_integer(subject)) {
                fail(c, pattern.start,
                     pattern.start + pattern.length,
                     "integer pattern requires integer subject");
                return NULL;
            }
        } else if (pattern.kind == TOKEN_TRUE ||
                   pattern.kind == TOKEN_FALSE) {
            if (subject->kind != MINI_TYPE_BOOL) {
                fail(c, pattern.start,
                     pattern.start + pattern.length,
                     "boolean pattern requires bool subject");
                return NULL;
            }
        } else if (pattern.kind == TOKEN_IDENTIFIER) {
            if (subject->kind != MINI_TYPE_RESOLVED_ENUM) {
                fail(c, pattern.start,
                     pattern.start + pattern.length,
                     "enum pattern requires enum subject");
                return NULL;
            }

            const MiniDecl *decl =
                subject->as.nominal.declaration;

            bool found = false;

            for (size_t j = 0;
                 j < decl->as.enum_decl.variant_count; j++) {
                Token variant = decl->as.enum_decl.variants[j];

                // Pattern contains "EnumName.Variant".
                Token enum_name = decl->name;

                size_t expected_length =
                    enum_name.length + 1 + variant.length;

                if (pattern.length != expected_length ||
                    pattern.start > c->source_length ||
                    pattern.length >
                        c->source_length - pattern.start)
                    continue;

                const char *text = c->source + pattern.start;

                if (memcmp(text,
                           c->source + enum_name.start,
                           enum_name.length) == 0 &&
                    text[enum_name.length] == '.' &&
                    memcmp(text + enum_name.length + 1,
                           c->source + variant.start,
                           variant.length) == 0) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                fail(c, pattern.start,
                     pattern.start + pattern.length,
                     "unknown enum pattern");
                return NULL;
            }
        } else {
            fail(c, pattern.start,
                 pattern.start + pattern.length,
                 "unsupported match pattern");
            return NULL;
        }

        for (size_t j = 0; j < i; j++) {
            if (arm->pattern.length ==
                    expr->as.match.arms[j].pattern.length &&
                arm->pattern.kind ==
                    expr->as.match.arms[j].pattern.kind &&
                memcmp(
                    c->source + arm->pattern.start,
                    c->source + expr->as.match.arms[j].pattern.start,
                    arm->pattern.length
                ) == 0) {
                fail(c, pattern.start,
                     pattern.start + pattern.length,
                     "duplicate match pattern");
                return NULL;
            }
        }

        const MiniResolvedType *arm_type =
            check_expr(c, arm->value);

        if (!arm_type)
            return NULL;

        if (result && !same(result, arm_type)) {
            fail(c, arm->value->start, arm->value->end,
                 "match arms must return the same type");
            return NULL;
        }

        result = arm_type;
    }

    if (!result) {
        fail(c, expr->start, expr->end, "empty match");
        return NULL;
    }

    if (!wildcard) {
        bool exhaustive = false;

        if (subject->kind == MINI_TYPE_BOOL) {
            bool has_true = false;
            bool has_false = false;

            for (size_t i = 0;
                 i < expr->as.match.arm_count; i++) {
                TokenKind kind = expr->as.match.arms[i].pattern.kind;
                has_true |= kind == TOKEN_TRUE;
                has_false |= kind == TOKEN_FALSE;
            }

            exhaustive = has_true && has_false;
        } else if (subject->kind == MINI_TYPE_RESOLVED_ENUM) {
            const MiniDecl *decl =
                subject->as.nominal.declaration;

            exhaustive =
                expr->as.match.arm_count ==
                decl->as.enum_decl.variant_count;
        }

        if (!exhaustive) {
            fail(c, expr->start, expr->end,
                 "non-exhaustive match; add a wildcard arm");
            return NULL;
        }
    }

    return result;
}


/* ------------------------------------------------------------
 * Expression checking
 * ------------------------------------------------------------ */

static bool is_lvalue(const MiniExpr *expr) {
    switch (expr->kind) {
        case MINI_EXPR_IDENTIFIER:
        case MINI_EXPR_INDEX:
            return true;

        case MINI_EXPR_MEMBER:
            return is_lvalue(expr->as.member.target);

        case MINI_EXPR_UNARY:
            return expr->as.unary.op.kind == TOKEN_STAR;

        default:
            return false;
    }
}

static const MiniResolvedType *check_expr(
    CheckContext *c,
    const MiniExpr *expr
) {
    if (!expr || !valid(c))
        return NULL;

    const MiniResolvedType *type = NULL;

    switch (expr->kind) {
        case MINI_EXPR_LAMBDA:
        case MINI_EXPR_CAST:
        case MINI_EXPR_SIZEOF:
            fail(c,expr->start,expr->end,"use mini_compile_project for v0.2 analysis");
            return NULL;
        case MINI_EXPR_INTEGER: {
            size_t value;

            if (!parse_size(c, expr->as.literal, &value) ||
                value > INT32_MAX) {
                fail(c, expr->start, expr->end,
                     "integer literal exceeds i32 range");
                return NULL;
            }

            type = builtin(c, MINI_TYPE_I32);
            break;
        }

        case MINI_EXPR_BOOL:
            type = builtin(c, MINI_TYPE_BOOL);
            break;

        case MINI_EXPR_STRING: {
            const MiniResolvedType *byte =
                builtin(c, MINI_TYPE_U8);

            type = mini_type_pointer(c->checked->types, byte);

            if (!type)
                oom(c);
            break;
        }

        case MINI_EXPR_IDENTIFIER: {
            MiniSymbolName name =
                token_name(c, expr->as.identifier);

            const MiniSymbol *symbol =
                mini_scope_lookup(c->scope, name);

            if (!symbol ||
                (symbol->kind != MINI_SYMBOL_VARIABLE &&
                 symbol->kind != MINI_SYMBOL_PARAMETER)) {
                fail(c, expr->start, expr->end,
                     "unknown variable");
                return NULL;
            }

            type = symbol->type;
            break;
        }

        case MINI_EXPR_UNARY: {
            const MiniResolvedType *operand =
                check_expr(c, expr->as.unary.operand);

            if (!operand)
                return NULL;

            switch (expr->as.unary.op.kind) {
                case TOKEN_MINUS:
                    if (operand->kind == MINI_TYPE_I32)
                        type = operand;
                    break;

                case TOKEN_BANG:
                    if (operand->kind == MINI_TYPE_BOOL)
                        type = operand;
                    break;

                case TOKEN_AMP:
                    if (is_lvalue(expr->as.unary.operand)) {
                        type = mini_type_pointer(
                            c->checked->types, operand
                        );
                        if (!type)
                            oom(c);
                    }
                    break;

                case TOKEN_STAR:
                    if (operand->kind ==
                        MINI_TYPE_RESOLVED_POINTER)
                        type = operand->as.pointer.element;
                    break;

                default:
                    break;
            }

            if (!type)
                fail(c, expr->start, expr->end,
                     "invalid unary operator");
            break;
        }

        case MINI_EXPR_BINARY: {
            const MiniResolvedType *left =
                check_expr(c, expr->as.binary.left);

            const MiniResolvedType *right =
                check_expr(c, expr->as.binary.right);

            if (!left || !right)
                return NULL;

            TokenKind op = expr->as.binary.op.kind;

            if (!same(left, right)) {
                fail(c, expr->start, expr->end,
                     "binary operand types differ");
                return NULL;
            }

            switch (op) {
                case TOKEN_PLUS:
                case TOKEN_MINUS:
                case TOKEN_STAR:
                case TOKEN_SLASH:
                case TOKEN_PERCENT:
                    if (mini_type_is_numeric(left))
                        type = left;
                    break;

                case TOKEN_LESS:
                case TOKEN_LESS_EQUAL:
                case TOKEN_GREATER:
                case TOKEN_GREATER_EQUAL:
                    if (mini_type_is_numeric(left))
                        type = builtin(c, MINI_TYPE_BOOL);
                    break;

                case TOKEN_EQUAL_EQUAL:
                case TOKEN_BANG_EQUAL:
                    if (mini_type_is_numeric(left) ||
                        left->kind == MINI_TYPE_BOOL ||
                        left->kind == MINI_TYPE_RESOLVED_ENUM ||
                        left->kind == MINI_TYPE_RESOLVED_POINTER)
                        type = builtin(c, MINI_TYPE_BOOL);
                    break;

                case TOKEN_AMP_AMP:
                case TOKEN_PIPE_PIPE:
                    if (left->kind == MINI_TYPE_BOOL)
                        type = builtin(c, MINI_TYPE_BOOL);
                    break;

                default:
                    break;
            }

            if (!type)
                fail(c, expr->start, expr->end,
                     "invalid binary operator");
            break;
        }

        case MINI_EXPR_ASSIGN: {
            const MiniExpr *target = expr->as.assign.target;

            if (!is_lvalue(target)) {
                fail(c, target->start, target->end,
                     "expression is not assignable");
                return NULL;
            }

            const MiniResolvedType *left =
                check_expr(c, target);

            const MiniResolvedType *right =
                check_expr(c, expr->as.assign.value);

            if (!left || !right)
                return NULL;

            if (!same(left, right)) {
                fail(c, expr->start, expr->end,
                     "assignment type mismatch");
                return NULL;
            }

            type = left;
            break;
        }

        case MINI_EXPR_CALL:
            type = check_call(c, expr);
            break;

        case MINI_EXPR_INDEX: {
            const MiniResolvedType *target =
                check_expr(c, expr->as.index.target);

            const MiniResolvedType *index =
                check_expr(c, expr->as.index.index);

            if (!target || !index)
                return NULL;

            if (!mini_type_is_integer(index)) {
                fail(c, expr->start, expr->end,
                     "array index must be an integer");
                return NULL;
            }

            if (target->kind == MINI_TYPE_RESOLVED_ARRAY)
                type = target->as.array.element;
            else if (target->kind ==
                     MINI_TYPE_RESOLVED_POINTER)
                type = target->as.pointer.element;
            else
                fail(c, expr->start, expr->end,
                     "indexing requires array or pointer");

            break;
        }

        case MINI_EXPR_MEMBER:
            type = check_member(c, expr);
            break;

        case MINI_EXPR_STRUCT:
            type = check_struct_literal(c, expr);
            break;

        case MINI_EXPR_MATCH:
            type = check_match(c, expr);
            break;
    }

    if (!type || !valid(c))
        return NULL;

    if (!remember_expr(c, expr, type))
        return NULL;

    return type;
}

/* ------------------------------------------------------------
 * Statements and lexical scopes
 * ------------------------------------------------------------ */

static bool check_stmt(CheckContext *c, const MiniStmt *stmt);

static bool check_block(CheckContext *c, const MiniStmt *block) {
    MiniScope *parent = c->scope;
    MiniScope *child = mini_scope_create(parent);

    if (!child) {
        oom(c);
        return false;
    }

    c->scope = child;

    for (size_t i = 0; i < block->as.block.count; i++) {
        if (!check_stmt(c, block->as.block.items[i]))
            break;
    }

    c->scope = parent;
    mini_scope_destroy(child);
    return valid(c);
}

static bool check_stmt(CheckContext *c, const MiniStmt *stmt) {
    if (!valid(c))
        return false;

    switch (stmt->kind) {
        case MINI_STMT_BLOCK:
            return check_block(c, stmt);

        case MINI_STMT_LET: {
            const MiniResolvedType *type =
                resolve_type(c, stmt->as.let.type);

            if (!type)
                return false;

            if (stmt->as.let.value) {
                const MiniResolvedType *actual =
                    check_expr(c, stmt->as.let.value);

                if (!actual)
                    return false;

                if (!same(type, actual)) {
                    fail(c, stmt->start, stmt->end,
                         "variable initializer type mismatch");
                    return false;
                }
            }

            MiniSymbol symbol = {
                .kind = MINI_SYMBOL_VARIABLE,
                .name = token_name(c, stmt->as.let.name),
                .type = type
            };

            if (mini_scope_lookup_local(
                    c->scope, symbol.name)) {
                fail(c, stmt->start, stmt->end,
                     "duplicate local variable");
                return false;
            }

            if (!mini_scope_define(c->scope, symbol)) {
                oom(c);
                return false;
            }

            return true;
        }

        case MINI_STMT_RETURN: {
            const MiniResolvedType *actual =
                stmt->as.ret.value
                    ? check_expr(c, stmt->as.ret.value)
                    : builtin(c, MINI_TYPE_VOID);

            if (!actual)
                return false;

            if (!same(c->return_type, actual)) {
                fail(c, stmt->start, stmt->end,
                     "return type mismatch");
                return false;
            }

            return true;
        }

        case MINI_STMT_IF: {
            const MiniResolvedType *condition =
                check_expr(c, stmt->as.if_stmt.condition);

            if (!condition)
                return false;

            if (condition->kind != MINI_TYPE_BOOL) {
                fail(c, stmt->start, stmt->end,
                     "if condition must be bool");
                return false;
            }

            if (!check_stmt(c, stmt->as.if_stmt.then_branch))
                return false;

            if (stmt->as.if_stmt.else_branch &&
                !check_stmt(c, stmt->as.if_stmt.else_branch))
                return false;

            return true;
        }

        case MINI_STMT_WHILE: {
            const MiniResolvedType *condition =
                check_expr(c, stmt->as.while_stmt.condition);

            if (!condition)
                return false;

            if (condition->kind != MINI_TYPE_BOOL) {
                fail(c, stmt->start, stmt->end,
                     "while condition must be bool");
                return false;
            }

            return check_stmt(c, stmt->as.while_stmt.body);
        }

        case MINI_STMT_EXPR:
            return check_expr(c, stmt->as.expr) != NULL;
    }

    fail(c, stmt->start, stmt->end, "unknown statement");
    return false;
}


/* ------------------------------------------------------------
 * Global declarations
 * ------------------------------------------------------------ */

static bool register_globals(CheckContext *c) {
    const MiniProgram *program = c->checked->ast;

    for (size_t i = 0; i < program->count; i++) {
        const MiniDecl *decl = program->items[i];
        MiniSymbolName name = token_name(c, decl->name);

        if (!valid(c))
            return false;

        // Do not allow declarations shadowing builtins.
        if (token_text(c, decl->name, "i32") ||
            token_text(c, decl->name, "u8") ||
            token_text(c, decl->name, "usize") ||
            token_text(c, decl->name, "bool")) {
            fail(c, decl->name.start,
                 decl->name.start + decl->name.length,
                 "reserved type name");
            return false;
        }

        if (mini_scope_lookup_local(c->globals, name)) {
            fail(c, decl->name.start,
                 decl->name.start + decl->name.length,
                 "duplicate global declaration");
            return false;
        }

        MiniSymbol symbol = {
            .name = name,
            .declaration = decl
        };

        if (decl->kind == MINI_DECL_FUNCTION) {
            symbol.kind = MINI_SYMBOL_FUNCTION;
            symbol.type = builtin(c, MINI_TYPE_VOID);
        } else {
            symbol.kind = MINI_SYMBOL_TYPE;
            symbol.type = mini_type_nominal(
                c->checked->types, decl
            );

            if (!symbol.type) {
                oom(c);
                return false;
            }
        }

        if (!mini_scope_define(c->globals, symbol)) {
            oom(c);
            return false;
        }
    }

    return true;
}

static bool check_declarations(CheckContext *c) {
    const MiniProgram *program = c->checked->ast;

    for (size_t i = 0; i < program->count; i++) {
        const MiniDecl *decl = program->items[i];

        if (decl->kind == MINI_DECL_FUNCTION) {
            const MiniType *return_ast =
                decl->as.function.return_type;

            const MiniResolvedType *return_type =
                return_ast
                    ? resolve_type(c, return_ast)
                    : builtin(c, MINI_TYPE_VOID);

            if (!return_type)
                return false;

            c->checked->return_types[i] = return_type;

            for (size_t j = 0;
                 j < decl->as.function.param_count; j++) {
                const MiniParameter *param =
                    &decl->as.function.params[j];

                if (!resolve_type(c, param->type))
                    return false;

                for (size_t k = 0; k < j; k++) {
                    if (token_eq(
                        c, param->name,
                        decl->as.function.params[k].name
                    )) {
                        fail(c, param->name.start,
                             param->name.start + param->name.length,
                             "duplicate parameter");
                        return false;
                    }
                }
            }
        }

        if (decl->kind == MINI_DECL_STRUCT) {
            for (size_t j = 0;
                 j < decl->as.struct_decl.field_count; j++) {
                const MiniParameter *field =
                    &decl->as.struct_decl.fields[j];

                if (!resolve_type(c, field->type))
                    return false;

                for (size_t k = 0; k < j; k++) {
                    if (token_eq(
                        c, field->name,
                        decl->as.struct_decl.fields[k].name
                    )) {
                        fail(c, field->name.start,
                             field->name.start + field->name.length,
                             "duplicate struct field");
                        return false;
                    }
                }
            }
        }

        if (decl->kind == MINI_DECL_ENUM) {
            for (size_t j = 0;
                 j < decl->as.enum_decl.variant_count; j++) {
                Token variant = decl->as.enum_decl.variants[j];

                for (size_t k = 0; k < j; k++) {
                    if (token_eq(
                        c, variant,
                        decl->as.enum_decl.variants[k]
                    )) {
                        fail(c, variant.start,
                             variant.start + variant.length,
                             "duplicate enum variant");
                        return false;
                    }
                }
            }
        }
    }

    return true;
}

static bool check_functions(CheckContext *c) {
    const MiniProgram *program = c->checked->ast;

    for (size_t i = 0; i < program->count; i++) {
        const MiniDecl *decl = program->items[i];

        if (decl->kind != MINI_DECL_FUNCTION ||
            decl->as.function.is_extern)
            continue;

        MiniScope *function_scope =
            mini_scope_create(c->globals);

        if (!function_scope) {
            oom(c);
            return false;
        }

        c->scope = function_scope;
        c->return_type = c->checked->return_types[i];

        for (size_t j = 0;
             j < decl->as.function.param_count; j++) {
            const MiniParameter *param =
                &decl->as.function.params[j];

            MiniSymbol symbol = {
                .kind = MINI_SYMBOL_PARAMETER,
                .name = token_name(c, param->name),
                .type = resolve_type(c, param->type)
            };

            if (!valid(c))
                break;

            if (!mini_scope_define(function_scope, symbol)) {
                oom(c);
                break;
            }
        }

        if (valid(c))
            check_stmt(c, decl->as.function.body);

        c->scope = c->globals;
        mini_scope_destroy(function_scope);

        if (!valid(c))
            return false;
    }

    return true;
}

/* ------------------------------------------------------------
 * Public API
 * ------------------------------------------------------------ */

MiniChecker *mini_checker_create(void) {
    return calloc(1, sizeof(MiniChecker));
}

void mini_checker_destroy(MiniChecker *checker) {
    free(checker);
}

MiniCheckResult mini_checker_check(
    MiniChecker *checker,
    const MiniProgram *program,
    const char *source,
    size_t source_length,
    MiniCheckedProgram **out_program
) {
    if (!out_program)
        return (MiniCheckResult){
            .status = MINI_CHECK_TYPE_ERROR,
            .message = "missing output pointer"
        };

    *out_program = NULL;

    if (!checker || !program || !source)
        return (MiniCheckResult){
            .status = MINI_CHECK_TYPE_ERROR,
            .message = "invalid checker input"
        };

    MiniCheckedProgram *checked =
        calloc(1, sizeof(*checked));

    if (!checked)
        return (MiniCheckResult){
            .status = MINI_CHECK_OUT_OF_MEMORY,
            .message = "out of memory"
        };

    checked->ast = program;
    checked->types = mini_type_context_create();

    if (!checked->types) {
        mini_checked_program_destroy(checked);
        return (MiniCheckResult){
            .status = MINI_CHECK_OUT_OF_MEMORY,
            .message = "out of memory"
        };
    }

    if (program->count >
        SIZE_MAX / sizeof(*checked->return_types)) {
        mini_checked_program_destroy(checked);
        return (MiniCheckResult){
            .status = MINI_CHECK_OUT_OF_MEMORY,
            .message = "too many declarations"
        };
    }

    checked->return_types = calloc(
        program->count ? program->count : 1,
        sizeof(*checked->return_types)
    );

    if (!checked->return_types) {
        mini_checked_program_destroy(checked);
        return (MiniCheckResult){
            .status = MINI_CHECK_OUT_OF_MEMORY,
            .message = "out of memory"
        };
    }

    CheckContext c = {
        .checked = checked,
        .source = source,
        .source_length = source_length,
        .result = ok()
    };

    c.globals = mini_scope_create(NULL);

    if (!c.globals) {
        mini_checked_program_destroy(checked);
        return (MiniCheckResult){
            .status = MINI_CHECK_OUT_OF_MEMORY,
            .message = "out of memory"
        };
    }

    c.scope = c.globals;

    if (register_globals(&c) &&
        check_declarations(&c)) {
        check_functions(&c);
    }

    mini_scope_destroy(c.globals);

    if (!valid(&c)) {
        MiniCheckResult result = c.result;
        mini_checked_program_destroy(checked);
        return result;
    }

    *out_program = checked;
    return ok();
}

const MiniResolvedType *mini_checked_expr_type(
    const MiniCheckedProgram *program,
    const MiniExpr *expression
) {
    if (!program || !expression)
        return NULL;

    for (size_t i = 0; i < program->expr_count; i++) {
        if (program->expr_types[i].expr == expression)
            return program->expr_types[i].type;
    }

    return NULL;
}

const MiniResolvedType *mini_checked_return_type(
    const MiniCheckedProgram *program,
    const MiniDecl *function
) {
    if (!program || !function)
        return NULL;

    for (size_t i = 0; i < program->ast->count; i++) {
        if (program->ast->items[i] == function)
            return program->return_types[i];
    }

    return NULL;
}

const MiniProgram *mini_checked_ast(
    const MiniCheckedProgram *program
) {
    return program ? program->ast : NULL;
}

void mini_checked_program_destroy(
    MiniCheckedProgram *program
) {
    if (!program)
        return;

    free(program->expr_types);
    free(program->return_types);

    mini_type_context_destroy(program->types);
    free(program);
}
