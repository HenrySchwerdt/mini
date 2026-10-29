#include "mini/ast.h"

#include <stdlib.h>

/* ------------------------------------------------------------
 * Expressions
 * ------------------------------------------------------------ */

void mini_pattern_free(MiniPattern *pattern) {
    if (pattern) {
        mini_pattern_free(pattern->payload);
        free(pattern);
    }
}

void mini_expr_free(MiniExpr *expr) {
    if (!expr)
        return;

    for (size_t i = 0; i < expr->type_arg_count; i++)
        mini_type_free(expr->type_args[i]);
    free(expr->type_args);
    switch (expr->kind) {
    case MINI_EXPR_CAST:
    case MINI_EXPR_SIZEOF:
        mini_type_free(expr->as.builtin.type);
        mini_expr_free(expr->as.builtin.value);
        break;
    case MINI_EXPR_INTEGER:
    case MINI_EXPR_STRING:
    case MINI_EXPR_BOOL:
    case MINI_EXPR_IDENTIFIER:
        break;

    case MINI_EXPR_UNARY:
        mini_expr_free(expr->as.unary.operand);
        break;

    case MINI_EXPR_BINARY:
        mini_expr_free(expr->as.binary.left);
        mini_expr_free(expr->as.binary.right);
        break;

    case MINI_EXPR_ASSIGN:
        mini_expr_free(expr->as.assign.target);
        mini_expr_free(expr->as.assign.value);
        break;

    case MINI_EXPR_CALL:
        mini_expr_free(expr->as.call.callee);

        for (size_t i = 0; i < expr->as.call.args.count; i++) {
            mini_expr_free(expr->as.call.args.items[i]);
        }

        free(expr->as.call.args.items);
        break;

    case MINI_EXPR_INDEX:
        mini_expr_free(expr->as.index.target);
        mini_expr_free(expr->as.index.index);
        break;

    case MINI_EXPR_MEMBER:
        mini_expr_free(expr->as.member.target);
        break;

    case MINI_EXPR_MATCH:
        mini_expr_free(expr->as.match.subject);

        for (size_t i = 0; i < expr->as.match.arm_count; i++) {
            mini_pattern_free(expr->as.match.arms[i].tree);
            mini_expr_free(expr->as.match.arms[i].value);
        }

        free(expr->as.match.arms);
        break;

    case MINI_EXPR_LAMBDA:
        for (size_t i = 0; i < expr->as.lambda.param_count; i++)
            mini_type_free(expr->as.lambda.params[i].type);
        free(expr->as.lambda.params);
        mini_stmt_free(expr->as.lambda.body);
        break;
    case MINI_EXPR_STRUCT:
        for (size_t i = 0; i < expr->as.struct_literal.field_count; i++) {
            mini_expr_free(expr->as.struct_literal.fields[i].value);
        }

        free(expr->as.struct_literal.fields);
        break;
    }

    free(expr);
}

/* ------------------------------------------------------------
 * Types
 * ------------------------------------------------------------ */

void mini_type_free(MiniType *type) {
    if (!type)
        return;

    for (size_t i = 0; i < type->arg_count; i++)
        mini_type_free(type->args[i]);
    free(type->args);
    switch (type->kind) {
    case MINI_TYPE_FUNCTION:
        mini_type_free(type->as.function.result);
        break;
    case MINI_TYPE_NAMED:
        break;

    case MINI_TYPE_POINTER:
        mini_type_free(type->as.pointer.element);
        break;

    case MINI_TYPE_ARRAY:
        mini_type_free(type->as.array.element);
        break;
    }

    free(type);
}

/* ------------------------------------------------------------
 * Statements
 * ------------------------------------------------------------ */

void mini_stmt_free(MiniStmt *stmt) {
    if (!stmt)
        return;

    switch (stmt->kind) {
    case MINI_STMT_LET:
        mini_type_free(stmt->as.let.type);
        mini_expr_free(stmt->as.let.value);
        break;

    case MINI_STMT_RETURN:
        mini_expr_free(stmt->as.ret.value);
        break;

    case MINI_STMT_IF:
        mini_expr_free(stmt->as.if_stmt.condition);

        mini_stmt_free(stmt->as.if_stmt.then_branch);

        mini_stmt_free(stmt->as.if_stmt.else_branch);
        break;

    case MINI_STMT_WHILE:
        mini_expr_free(stmt->as.while_stmt.condition);

        mini_stmt_free(stmt->as.while_stmt.body);
        break;

    case MINI_STMT_BLOCK:
        for (size_t i = 0; i < stmt->as.block.count; i++) {
            mini_stmt_free(stmt->as.block.items[i]);
        }

        free(stmt->as.block.items);
        break;

    case MINI_STMT_EXPR:
        mini_expr_free(stmt->as.expr);
        break;
    }

    free(stmt);
}

/* ------------------------------------------------------------
 * Declarations
 * ------------------------------------------------------------ */

void mini_annotations_free(MiniAnnotation *annotations, size_t count) {
    for (size_t i = 0; i < count; i++) {
        for (size_t j = 0; j < annotations[i].arg_count; j++)
            mini_expr_free(annotations[i].args[j]);
        free(annotations[i].args);
    }
    free(annotations);
}
void mini_decl_free(MiniDecl *decl) {
    if (!decl)
        return;

    mini_annotations_free(decl->annotations, decl->annotation_count);
    for (size_t i = 0; i < decl->generic_count; i++)
        mini_type_free(decl->generics[i].bound);
    free(decl->generics);
    switch (decl->kind) {
    case MINI_DECL_FUNCTION:
        for (size_t i = 0; i < decl->as.function.param_count; i++) {
            mini_type_free(decl->as.function.params[i].type);
        }

        free(decl->as.function.params);

        mini_type_free(decl->as.function.return_type);

        mini_stmt_free(decl->as.function.body);
        break;

    case MINI_DECL_STRUCT:
        for (size_t i = 0; i < decl->as.struct_decl.field_count; i++)
            mini_annotations_free(decl->as.struct_decl.fields[i].annotations,
                                  decl->as.struct_decl.fields[i].annotation_count);
        for (size_t i = 0; i < decl->as.struct_decl.field_count; i++) {
            mini_type_free(decl->as.struct_decl.fields[i].type);
        }

        free(decl->as.struct_decl.fields);
        for (size_t i = 0; i < decl->as.struct_decl.super_count; i++)
            mini_type_free(decl->as.struct_decl.supers[i]);
        free(decl->as.struct_decl.supers);
        for (size_t i = 0; i < decl->as.struct_decl.method_count; i++)
            mini_decl_free(decl->as.struct_decl.methods[i]);
        free(decl->as.struct_decl.methods);
        break;

    case MINI_DECL_ENUM:
        free(decl->as.enum_decl.variants);
        break;
    }

    free(decl);
}

/* ------------------------------------------------------------
 * Program
 * ------------------------------------------------------------ */

void mini_program_free(MiniProgram *program) {
    if (!program)
        return;

    for (size_t i = 0; i < program->count; i++) {
        mini_decl_free(program->items[i]);
    }

    free(program->imports);
    free(program->items);
    free(program);
}
