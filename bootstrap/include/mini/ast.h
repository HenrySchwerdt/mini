#ifndef MINI_AST_H
#define MINI_AST_H

#include <stdbool.h>
#include <stddef.h>

#include "mini/token.h"

/* Forward declarations */

typedef struct MiniExpr MiniExpr;
typedef struct MiniType MiniType;
typedef struct MiniStmt MiniStmt;
typedef struct MiniDecl MiniDecl;

/* Expressions */

typedef enum {
    MINI_EXPR_CAST,
    MINI_EXPR_SIZEOF,
    MINI_EXPR_INTEGER,
    MINI_EXPR_STRING,
    MINI_EXPR_BOOL,
    MINI_EXPR_IDENTIFIER,

    MINI_EXPR_UNARY,
    MINI_EXPR_BINARY,
    MINI_EXPR_ASSIGN,

    MINI_EXPR_CALL,
    MINI_EXPR_INDEX,
    MINI_EXPR_MEMBER,
    MINI_EXPR_MATCH,
    MINI_EXPR_STRUCT,
    MINI_EXPR_LAMBDA
} MiniExprKind;

typedef struct {
    MiniExpr **items;
    size_t count;
} MiniExprList;

typedef struct MiniPattern {
    Token name;
    struct MiniPattern *payload;
} MiniPattern;

typedef struct {
    MiniPattern *tree;
    Token pattern;
    MiniExpr *value;
} MiniMatchArm;

typedef struct {
    Token name;
    MiniExpr *value;
} MiniFieldInit;

typedef struct {
    Token name;
    MiniType *type;
} MiniLambdaParameter;

struct MiniExpr {
    MiniExprKind kind;
    size_t start;
    size_t end;
    MiniType **type_args;
    size_t type_arg_count;

    union {
        struct {
            MiniLambdaParameter *params;
            size_t param_count;
            bool explicit_params;
            MiniStmt *body;
        } lambda;
        struct {
            MiniType *type;
            MiniExpr *value;
        } builtin;
        Token literal;
        Token identifier;

        struct {
            Token op;
            MiniExpr *operand;
        } unary;

        struct {
            Token op;
            MiniExpr *left;
            MiniExpr *right;
        } binary;

        struct {
            MiniExpr *target;
            MiniExpr *value;
        } assign;

        struct {
            MiniExpr *callee;
            MiniExprList args;
        } call;

        struct {
            MiniExpr *target;
            MiniExpr *index;
        } index;

        struct {
            MiniExpr *target;
            Token name;
        } member;

        struct {
            MiniExpr *subject;
            MiniMatchArm *arms;
            size_t arm_count;
        } match;

        struct {
            Token type_name;
            MiniFieldInit *fields;
            size_t field_count;
        } struct_literal;
    } as;
};

/* Types */

typedef enum {
    MINI_TYPE_NAMED,
    MINI_TYPE_POINTER,
    MINI_TYPE_ARRAY,
    MINI_TYPE_FUNCTION
} MiniTypeKind;

struct MiniType {
    MiniTypeKind kind;
    size_t start;
    size_t end;
    MiniType **args;
    size_t arg_count;

    union {
        Token name;

        struct {
            MiniType *element;
        } pointer;

        struct {
            MiniType *element;
            Token length;
        } array;
        struct {
            MiniType *result;
        } function;
    } as;
};

/* Statements */

typedef enum {
    MINI_STMT_LET,
    MINI_STMT_RETURN,
    MINI_STMT_IF,
    MINI_STMT_WHILE,
    MINI_STMT_BLOCK,
    MINI_STMT_EXPR
} MiniStmtKind;

struct MiniStmt {
    MiniStmtKind kind;
    size_t start;
    size_t end;

    union {
        struct {
            Token name;
            MiniType *type;
            MiniExpr *value;
            bool immutable;
        } let;

        struct {
            MiniExpr *value;
        } ret;

        struct {
            MiniExpr *condition;
            MiniStmt *then_branch;
            MiniStmt *else_branch;
        } if_stmt;

        struct {
            MiniExpr *condition;
            MiniStmt *body;
        } while_stmt;

        struct {
            MiniStmt **items;
            size_t count;
        } block;

        MiniExpr *expr;
    } as;
};

/* Declarations */

typedef struct {
    Token name;
    MiniExpr **args;
    size_t arg_count;
} MiniAnnotation;

typedef struct {
    Token name;
    MiniType *type;
    unsigned flags;
    MiniAnnotation *annotations;
    size_t annotation_count;
} MiniParameter;

typedef struct {
    Token name;
    MiniType *bound;
} MiniGeneric;
enum {
    MINI_OPEN = 1,
    MINI_ABSTRACT = 2,
    MINI_OVERRIDE = 4,
    MINI_FINAL = 8,
    MINI_PRIVATE = 16,
    MINI_PROTECTED = 32,
    MINI_INTERNAL = 64,
    MINI_PUBLIC = 128,
    MINI_STATIC = 256,
    MINI_IMMUTABLE = 512
};

typedef enum { MINI_DECL_FUNCTION, MINI_DECL_STRUCT, MINI_DECL_ENUM } MiniDeclKind;

struct MiniDecl {
    MiniDeclKind kind;
    Token name;
    unsigned flags;
    MiniAnnotation *annotations;
    size_t annotation_count;
    MiniGeneric *generics;
    size_t generic_count;

    union {
        struct {
            MiniParameter *params;
            size_t param_count;
            MiniType *return_type;
            MiniStmt *body;
            bool is_extern;
            bool expression_body;
        } function;

        struct {
            MiniParameter *fields;
            size_t field_count;
            bool is_class;
            bool is_interface;
            bool is_annotation;
            MiniType **supers;
            size_t super_count;
            MiniDecl **methods;
            size_t method_count;
        } struct_decl;

        struct {
            Token *variants;
            size_t variant_count;
        } enum_decl;
    } as;
};

/* Program */

typedef struct {
    Token path;
    Token alias;
    bool star;
} MiniImport;

typedef struct {
    MiniDecl **items;
    size_t count;
    Token package_name;
    MiniImport *imports;
    size_t import_count;
} MiniProgram;

/* Memory management */

void mini_annotations_free(MiniAnnotation *annotations, size_t count);
void mini_pattern_free(MiniPattern *pattern);
void mini_expr_free(MiniExpr *expr);
void mini_type_free(MiniType *type);
void mini_stmt_free(MiniStmt *stmt);
void mini_decl_free(MiniDecl *decl);
void mini_program_free(MiniProgram *program);

#endif
