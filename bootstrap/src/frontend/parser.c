#include "mini/parser.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define BP_ASSIGN 1
#define BP_OR 2
#define BP_AND 3
#define BP_EQUAL 4
#define BP_COMPARE 5
#define BP_ADD 6
#define BP_MUL 7
#define BP_PREFIX 8
#define BP_POSTFIX 9

static MiniExpr *parse_bp(MiniParser *p, int min_bp);
static MiniStmt *parse_statement(MiniParser *p);
static MiniStmt *parse_block(MiniParser *p);

static size_t token_end(Token token) {
    return token.start + token.length;
}

static void error(MiniParser *p, const char *message) {
    if (p->has_error)
        return;

    p->has_error = true;
    p->error_token = p->current;
    p->error_message = message;
}

static void advance(MiniParser *p) {
    p->previous = p->current;
    p->current = lexer_next(&p->lexer);

    if (p->current.kind == TOKEN_ERROR) {
        error(p, "invalid token");
    }
}

static bool accept(MiniParser *p, TokenKind kind) {
    if (p->has_error || p->current.kind != kind)
        return false;

    advance(p);
    return true;
}

static bool expect(MiniParser *p, TokenKind kind) {
    if (p->has_error)
        return false;

    if (p->current.kind != kind) {
        error(p, "unexpected token");
        return false;
    }

    advance(p);
    return !p->has_error;
}

/* Newlines in trivia (including comments and CRLF) terminate complete outer
 * expressions. Grouped expressions and a preceding operator can continue. */
static bool line_break(const MiniParser *p) {
    for (size_t i = token_end(p->previous); i < p->current.start; i++)
        if (p->lexer.source[i] == '\n' || p->lexer.source[i] == '\r')
            return true;
    return false;
}
static bool statement_end(MiniParser *p) {
    if (p->has_error)
        return false;
    if (accept(p, TOKEN_SEMICOLON))
        return true;
    if (p->current.kind == TOKEN_RBRACE || p->current.kind == TOKEN_EOF || line_break(p))
        return true;
    error(p, "expected ';' or a line break");
    return false;
}

static bool expect_identifier(MiniParser *p, Token *out) {
    if (p->has_error)
        return false;

    if (p->current.kind != TOKEN_IDENTIFIER) {
        error(p, "expected identifier");
        return false;
    }

    if (p->current.length == 4 && !memcmp(p->lexer.source + p->current.start, "null", 4)) {
        error(p, "null is not part of Mini; use Optional.None for absence");
        return false;
    }
    *out = p->current;
    advance(p);
    return !p->has_error;
}

static bool append(MiniParser *p, void **items, size_t *count, size_t element_size,
                   const void *item) {
    if (element_size == 0 || *count >= SIZE_MAX / element_size) {
        error(p, "collection too large");
        return false;
    }

    void *next = realloc(*items, (*count + 1) * element_size);

    if (!next) {
        error(p, "out of memory");
        return false;
    }

    *items = next;

    memcpy((char *)next + *count * element_size, item, element_size);

    (*count)++;
    return true;
}

static MiniExpr *new_expr(MiniParser *p, MiniExprKind kind, size_t start, size_t end) {
    MiniExpr *expr = calloc(1, sizeof(*expr));

    if (!expr) {
        error(p, "out of memory");
        return NULL;
    }

    expr->kind = kind;
    expr->start = start;
    expr->end = end;
    return expr;
}

static MiniStmt *new_stmt(MiniParser *p, MiniStmtKind kind, size_t start) {
    MiniStmt *stmt = calloc(1, sizeof(*stmt));

    if (!stmt) {
        error(p, "out of memory");
        return NULL;
    }

    stmt->kind = kind;
    stmt->start = start;
    return stmt;
}

static MiniDecl *new_decl(MiniParser *p, MiniDeclKind kind, Token name) {
    MiniDecl *decl = calloc(1, sizeof(*decl));

    if (!decl) {
        error(p, "out of memory");
        return NULL;
    }

    decl->kind = kind;
    decl->name = name;
    return decl;
}

static void finish_stmt(MiniParser *p, MiniStmt *stmt) {
    stmt->end = token_end(p->previous);
}

/* ------------------------------------------------------------
 * Parser initialization
 * ------------------------------------------------------------ */

MiniParser mini_parser_new(const char *source, size_t length) {
    MiniParser p = {0};

    p.lexer = lexer_new(source, length);
    p.allow_struct_literal = true;

    p.current = lexer_next(&p.lexer);

    if (p.current.kind == TOKEN_ERROR) {
        p.has_error = true;
        p.error_token = p.current;
        p.error_message = "invalid token";
    }

    return p;
}

bool mini_parser_has_error(const MiniParser *p) {
    return p->has_error;
}

/* ------------------------------------------------------------
 * Pratt operator precedence
 * ------------------------------------------------------------ */

static bool infix_bp(TokenKind kind, int *left, int *right) {
    int bp;

    switch (kind) {
    case TOKEN_EQUAL:
        *left = BP_ASSIGN;
        *right = BP_ASSIGN;
        return true;

    case TOKEN_PIPE_PIPE:
        bp = BP_OR;
        break;

    case TOKEN_AMP_AMP:
        bp = BP_AND;
        break;

    case TOKEN_EQUAL_EQUAL:
    case TOKEN_BANG_EQUAL:
        bp = BP_EQUAL;
        break;

    case TOKEN_LESS:
    case TOKEN_LESS_EQUAL:
    case TOKEN_GREATER:
    case TOKEN_GREATER_EQUAL:
        bp = BP_COMPARE;
        break;

    case TOKEN_PLUS:
    case TOKEN_MINUS:
        bp = BP_ADD;
        break;

    case TOKEN_STAR:
    case TOKEN_SLASH:
    case TOKEN_PERCENT:
        bp = BP_MUL;
        break;

    default:
        return false;
    }

    *left = bp;
    *right = bp + 1;
    return true;
}

static bool close_generic(MiniParser *p) {
    if (p->current.kind == TOKEN_GREATER_EQUAL) {
        p->previous = (Token){TOKEN_GREATER, p->current.start, 1};
        p->current = (Token){TOKEN_EQUAL, p->current.start + 1, 1};
        return true;
    }
    return expect(p, TOKEN_GREATER);
}

static bool type_arguments(MiniParser *p, MiniType ***args, size_t *count) {
    if (!expect(p, TOKEN_LESS))
        return false;
    do {
        MiniType *arg = mini_parse_type(p);
        if (!arg)
            return false;
        if (!append(p, (void **)args, count, sizeof(arg), &arg)) {
            mini_type_free(arg);
            return false;
        }
    } while (accept(p, TOKEN_COMMA));
    return close_generic(p);
}

static bool generic_parameters(MiniParser *p, MiniDecl *decl) {
    if (!accept(p, TOKEN_LESS))
        return true;
    do {
        MiniGeneric generic = {0};
        if (!expect_identifier(p, &generic.name))
            return false;
        if (accept(p, TOKEN_COLON)) {
            generic.bound = mini_parse_type(p);
            if (!generic.bound)
                return false;
        }
        if (!append(p, (void **)&decl->generics, &decl->generic_count, sizeof(generic), &generic)) {
            mini_type_free(generic.bound);
            return false;
        }
    } while (accept(p, TOKEN_COMMA));
    return close_generic(p);
}

static unsigned modifiers(MiniParser *p) {
    unsigned flags = 0;
    for (;;) {
        unsigned bit;
        switch (p->current.kind) {
        case TOKEN_STATIC:
            bit = MINI_STATIC;
            break;
        case TOKEN_OPEN:
            bit = MINI_OPEN;
            break;
        case TOKEN_ABSTRACT:
            bit = MINI_ABSTRACT;
            break;
        case TOKEN_OVERRIDE:
            bit = MINI_OVERRIDE;
            break;
        case TOKEN_FINAL:
            bit = MINI_FINAL;
            break;
        case TOKEN_PUBLIC:
            bit = MINI_PUBLIC;
            break;
        case TOKEN_PRIVATE:
            bit = MINI_PRIVATE;
            break;
        case TOKEN_PROTECTED:
            bit = MINI_PROTECTED;
            break;
        case TOKEN_INTERNAL:
            bit = MINI_INTERNAL;
            break;
        default:
            return flags;
        }
        if (flags & bit) {
            error(p, "duplicate modifier");
            return flags;
        }
        flags |= bit;
        advance(p);
    }
}

static bool qualified_name(MiniParser *p, Token *name) {
    if (!expect_identifier(p, name))
        return false;
    while (p->current.kind == TOKEN_DOT) {
        MiniParser saved = *p;
        advance(p);
        if (p->current.kind != TOKEN_IDENTIFIER) {
            *p = saved;
            break;
        }
        advance(p);
        name->length = token_end(p->previous) - name->start;
    }
    return true;
}

static bool annotations(MiniParser *p, MiniAnnotation **items, size_t *count) {
    while (accept(p, TOKEN_AT)) {
        MiniAnnotation a = {0};
        if (!qualified_name(p, &a.name))
            return false;
        if (accept(p, TOKEN_LPAREN)) {
            if (p->current.kind != TOKEN_RPAREN)
                do {
                    p->continuation++;
                    MiniExpr *value = parse_bp(p, 0);
                    p->continuation--;
                    if (!value)
                        goto fail;
                    if (!append(p, (void **)&a.args, &a.arg_count, sizeof(value), &value)) {
                        mini_expr_free(value);
                        goto fail;
                    }
                } while (accept(p, TOKEN_COMMA));
            if (!expect(p, TOKEN_RPAREN))
                goto fail;
        }
        if (!append(p, (void **)items, count, sizeof(a), &a))
            goto fail;
        continue;
    fail:
        for (size_t i = 0; i < a.arg_count; i++)
            mini_expr_free(a.args[i]);
        free(a.args);
        return false;
    }
    return true;
}

/* ------------------------------------------------------------
 * Struct literals
 * ------------------------------------------------------------ */

static MiniExpr *parse_struct_literal(MiniParser *p, MiniExpr *name) {
    Token type_name = name->as.identifier;
    size_t start = name->start;

    MiniFieldInit *fields = NULL;
    size_t count = 0;

    if (!expect(p, TOKEN_LBRACE))
        goto fail;

    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        MiniFieldInit field = {0};

        if (!expect_identifier(p, &field.name))
            goto fail;

        if (!expect(p, TOKEN_COLON))
            goto fail;

        field.value = parse_bp(p, 0);
        if (!field.value)
            goto fail;

        if (!append(p, (void **)&fields, &count, sizeof(*fields), &field)) {
            mini_expr_free(field.value);
            goto fail;
        }

        if (accept(p, TOKEN_COMMA))
            continue;

        if (p->current.kind != TOKEN_RBRACE) {
            error(p, "expected ',' or '}'");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RBRACE))
        goto fail;

    MiniExpr *expr = new_expr(p, MINI_EXPR_STRUCT, start, token_end(p->previous));

    if (!expr)
        goto fail;

    expr->as.struct_literal.type_name = type_name;
    expr->type_args = name->type_args;
    expr->type_arg_count = name->type_arg_count;
    name->type_args = NULL;
    name->type_arg_count = 0;
    expr->as.struct_literal.fields = fields;
    expr->as.struct_literal.field_count = count;

    mini_expr_free(name);
    return expr;

fail:
    for (size_t i = 0; i < count; i++)
        mini_expr_free(fields[i].value);

    free(fields);
    mini_expr_free(name);
    return NULL;
}

/* ------------------------------------------------------------
 * Match expressions
 * ------------------------------------------------------------ */

static MiniPattern *parse_pattern_inner(MiniParser *p);
static MiniPattern *parse_pattern(MiniParser *p) {
    if (++p->nesting > 256) {
        error(p, "syntax nesting limit exceeded");
        p->nesting--;
        return NULL;
    }
    MiniPattern *result = parse_pattern_inner(p);
    p->nesting--;
    return result;
}
static MiniPattern *parse_pattern_inner(MiniParser *p) {
    MiniPattern *pattern = calloc(1, sizeof(*pattern));
    if (!pattern) {
        error(p, "out of memory");
        return NULL;
    }
    pattern->name = p->current;
    switch (p->current.kind) {
    case TOKEN_INTEGER:
    case TOKEN_TRUE:
    case TOKEN_FALSE:
    case TOKEN_UNDERSCORE:
        advance(p);
        break;
    case TOKEN_IDENTIFIER:
        if (!qualified_name(p, &pattern->name))
            goto fail;
        if (accept(p, TOKEN_LPAREN)) {
            pattern->payload = parse_pattern(p);
            if (!pattern->payload || !expect(p, TOKEN_RPAREN))
                goto fail;
        }
        break;
    default:
        error(p, "expected match pattern");
        goto fail;
    }
    return pattern;
fail:
    mini_pattern_free(pattern);
    return NULL;
}

static MiniExpr *parse_match(MiniParser *p) {
    size_t start = p->current.start;
    advance(p); // match

    bool previous = p->allow_struct_literal;
    p->allow_struct_literal = false;

    MiniExpr *subject = parse_bp(p, 0);

    p->allow_struct_literal = previous;

    if (!subject)
        return NULL;

    MiniMatchArm *arms = NULL;
    size_t count = 0;

    if (!expect(p, TOKEN_LBRACE))
        goto fail;

    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        MiniPattern *tree = parse_pattern(p);
        if (!tree)
            goto fail;
        if (!expect(p, TOKEN_FAT_ARROW)) {
            mini_pattern_free(tree);
            goto fail;
        }
        MiniExpr *value = parse_bp(p, 0);
        if (!value) {
            mini_pattern_free(tree);
            goto fail;
        }
        MiniMatchArm arm = {.pattern = tree->name, .tree = tree, .value = value};

        if (!append(p, (void **)&arms, &count, sizeof(*arms), &arm)) {
            mini_pattern_free(tree);
            mini_expr_free(value);
            goto fail;
        }

        if (accept(p, TOKEN_COMMA))
            continue;

        if (p->current.kind != TOKEN_RBRACE && !line_break(p)) {
            error(p, "expected ',' or '}'");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RBRACE))
        goto fail;

    MiniExpr *expr = new_expr(p, MINI_EXPR_MATCH, start, token_end(p->previous));

    if (!expr)
        goto fail;

    expr->as.match.subject = subject;
    expr->as.match.arms = arms;
    expr->as.match.arm_count = count;

    return expr;

fail:
    mini_expr_free(subject);

    for (size_t i = 0; i < count; i++) {
        mini_pattern_free(arms[i].tree);
        mini_expr_free(arms[i].value);
    }

    free(arms);
    return NULL;
}

/* ------------------------------------------------------------
 * Prefix expressions
 * ------------------------------------------------------------ */

static MiniStmt *parse_statement(MiniParser *p);
static MiniStmt *parse_expr_stmt(MiniParser *p);
static MiniExpr *parse_lambda(MiniParser *p) {
    size_t start = p->current.start;
    advance(p);
    MiniExpr *e = new_expr(p, MINI_EXPR_LAMBDA, start, start);
    if (!e)
        return NULL;
    MiniParser trial = *p;
    MiniLambdaParameter *params = NULL;
    size_t count = 0;
    bool header = trial.current.kind == TOKEN_ARROW;
    while (!header && trial.current.kind == TOKEN_IDENTIFIER && !trial.has_error) {
        MiniLambdaParameter param = {.name = trial.current};
        advance(&trial);
        if (accept(&trial, TOKEN_COLON))
            param.type = mini_parse_type(&trial);
        if (trial.has_error || !append(&trial, (void **)&params, &count, sizeof(param), &param)) {
            mini_type_free(param.type);
            break;
        }
        if (trial.current.kind == TOKEN_ARROW) {
            header = true;
            break;
        }
        if (!accept(&trial, TOKEN_COMMA))
            break;
    }
    if (header && !trial.has_error) {
        *p = trial;
        advance(p);
        e->as.lambda.params = params;
        e->as.lambda.param_count = count;
        e->as.lambda.explicit_params = true;
    } else {
        for (size_t i = 0; i < count; i++)
            mini_type_free(params[i].type);
        free(params);
    }
    MiniStmt *body = new_stmt(p, MINI_STMT_BLOCK, start);
    if (!body) {
        mini_expr_free(e);
        return NULL;
    }
    e->as.lambda.body = body;
    size_t continuation = p->continuation;
    bool literals = p->allow_struct_literal;
    p->continuation = 0;
    p->allow_struct_literal = true;
    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        MiniStmt *statement =
            p->current.kind == TOKEN_LBRACE ? parse_expr_stmt(p) : parse_statement(p);
        if (!statement)
            break;
        if (!append(p, (void **)&body->as.block.items, &body->as.block.count, sizeof(statement),
                    &statement)) {
            mini_stmt_free(statement);
            break;
        }
    }
    p->continuation = continuation;
    p->allow_struct_literal = literals;
    if (!expect(p, TOKEN_RBRACE) || p->has_error) {
        mini_expr_free(e);
        return NULL;
    }
    e->end = token_end(p->previous);
    body->end = e->end;
    return e;
}

static MiniExpr *parse_prefix(MiniParser *p) {
    if (p->has_error)
        return NULL;

    Token token = p->current;
    if (token.kind == TOKEN_IDENTIFIER) {
        const char *text = p->lexer.source + token.start;
        bool is_cast = token.length == 4 && memcmp(text, "cast", 4) == 0;
        bool is_size = token.length == 6 && memcmp(text, "sizeof", 6) == 0;
        if (token.length == 4 && memcmp(text, "null", 4) == 0) {
            error(p, "null is not part of Mini; use Optional.None for absence");
            return NULL;
        }
        if (is_cast || is_size) {
            advance(p);
            MiniExpr *e = new_expr(p, is_cast ? MINI_EXPR_CAST : MINI_EXPR_SIZEOF, token.start,
                                   token_end(token));
            if (!e)
                return NULL;
            if (!expect(p, is_cast ? TOKEN_LESS : TOKEN_LPAREN)) {
                mini_expr_free(e);
                return NULL;
            }
            e->as.builtin.type = mini_parse_type(p);
            if (!e->as.builtin.type || !expect(p, is_cast ? TOKEN_GREATER : TOKEN_RPAREN)) {
                mini_expr_free(e);
                return NULL;
            }
            if (is_cast) {
                if (!expect(p, TOKEN_LPAREN)) {
                    mini_expr_free(e);
                    return NULL;
                }
                p->continuation++;
                e->as.builtin.value = parse_bp(p, 0);
                p->continuation--;
                if (!e->as.builtin.value || !expect(p, TOKEN_RPAREN)) {
                    mini_expr_free(e);
                    return NULL;
                }
            }
            e->end = token_end(p->previous);
            return e;
        }
    }

    switch (token.kind) {
    case TOKEN_LBRACE:
        return parse_lambda(p);
    case TOKEN_INTEGER:
    case TOKEN_STRING:
    case TOKEN_TRUE:
    case TOKEN_FALSE:
    case TOKEN_IDENTIFIER: {
        MiniExprKind kind;

        switch (token.kind) {
        case TOKEN_INTEGER:
            kind = MINI_EXPR_INTEGER;
            break;

        case TOKEN_STRING:
            kind = MINI_EXPR_STRING;
            break;

        case TOKEN_TRUE:
        case TOKEN_FALSE:
            kind = MINI_EXPR_BOOL;
            break;

        default:
            kind = MINI_EXPR_IDENTIFIER;
            break;
        }

        MiniExpr *expr = new_expr(p, kind, token.start, token_end(token));

        if (!expr)
            return NULL;

        if (kind == MINI_EXPR_IDENTIFIER)
            expr->as.identifier = token;
        else
            expr->as.literal = token;

        advance(p);

        if (p->has_error) {
            mini_expr_free(expr);
            return NULL;
        }

        if (kind == MINI_EXPR_IDENTIFIER && token.length == 5 &&
            !memcmp(p->lexer.source + token.start, "super", 5) && p->current.kind == TOKEN_LESS) {
            if (!type_arguments(p, &expr->type_args, &expr->type_arg_count)) {
                mini_expr_free(expr);
                return NULL;
            }
        }
        if (kind == MINI_EXPR_IDENTIFIER && p->allow_struct_literal &&
            (!line_break(p) || p->continuation) && p->current.kind == TOKEN_LESS) {
            MiniParser trial = *p;
            MiniType **args = NULL;
            size_t count = 0;
            if (type_arguments(&trial, &args, &count) &&
                (trial.current.kind == TOKEN_LBRACE || trial.current.kind == TOKEN_DOT)) {
                *p = trial;
                expr->type_args = args;
                expr->type_arg_count = count;
            } else {
                for (size_t i = 0; i < count; i++)
                    mini_type_free(args[i]);
                free(args);
            }
        }
        if (kind == MINI_EXPR_IDENTIFIER && p->allow_struct_literal &&
            (!line_break(p) || p->continuation) && p->current.kind == TOKEN_LBRACE) {
            return parse_struct_literal(p, expr);
        }

        return expr;
    }

    case TOKEN_LPAREN: {
        advance(p);
        bool saved = p->allow_struct_literal;
        p->allow_struct_literal = true;
        p->continuation++;
        MiniExpr *expr = parse_bp(p, 0);
        p->continuation--;
        p->allow_struct_literal = saved;
        if (!expr)
            return NULL;

        if (!expect(p, TOKEN_RPAREN)) {
            mini_expr_free(expr);
            return NULL;
        }

        return expr;
    }

    case TOKEN_MINUS:
    case TOKEN_BANG:
    case TOKEN_STAR:
    case TOKEN_AMP: {
        advance(p);

        MiniExpr *operand = parse_bp(p, BP_PREFIX);

        if (!operand)
            return NULL;

        MiniExpr *expr = new_expr(p, MINI_EXPR_UNARY, token.start, operand->end);

        if (!expr) {
            mini_expr_free(operand);
            return NULL;
        }

        expr->as.unary.op = token;
        expr->as.unary.operand = operand;
        return expr;
    }

    case TOKEN_MATCH:
        return parse_match(p);

    default:
        error(p, "expected expression");
        return NULL;
    }
}

/* ------------------------------------------------------------
 * Function calls
 * ------------------------------------------------------------ */

static MiniExpr *parse_call(MiniParser *p, MiniExpr *callee) {
    MiniExprList args = {0};

    advance(p); // (
    p->continuation++;

    while (!p->has_error && p->current.kind != TOKEN_RPAREN && p->current.kind != TOKEN_EOF) {
        MiniExpr *arg = parse_bp(p, 0);
        if (!arg)
            goto fail;

        if (!append(p, (void **)&args.items, &args.count, sizeof(*args.items), &arg)) {
            mini_expr_free(arg);
            goto fail;
        }

        if (accept(p, TOKEN_COMMA))
            continue;

        if (p->current.kind != TOKEN_RPAREN) {
            error(p, "expected ',' or ')'");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RPAREN))
        goto fail;

    MiniExpr *expr = new_expr(p, MINI_EXPR_CALL, callee->start, token_end(p->previous));

    if (!expr)
        goto fail;

    expr->as.call.callee = callee;
    expr->as.call.args = args;
    p->continuation--;
    return expr;

fail:
    p->continuation--;
    for (size_t i = 0; i < args.count; i++)
        mini_expr_free(args.items[i]);

    free(args.items);
    mini_expr_free(callee);
    return NULL;
}

/* ------------------------------------------------------------
 * Pratt expression parser
 * ------------------------------------------------------------ */

static MiniExpr *parse_bp_inner(MiniParser *p, int min_bp);
static MiniExpr *parse_bp(MiniParser *p, int min_bp) {
    if (++p->nesting > 256) {
        error(p, "syntax nesting limit exceeded");
        p->nesting--;
        return NULL;
    }
    MiniExpr *result = parse_bp_inner(p, min_bp);
    p->nesting--;
    return result;
}
static MiniExpr *parse_bp_inner(MiniParser *p, int min_bp) {
    MiniExpr *left = parse_prefix(p);
    if (!left)
        return NULL;

    while (!p->has_error) {
        if (!p->continuation && line_break(p) && p->current.kind != TOKEN_DOT &&
            p->current.kind != TOKEN_SCOPE)
            break;
        if (p->allow_struct_literal && p->current.kind == TOKEN_LBRACE &&
            left->kind == MINI_EXPR_CALL && BP_POSTFIX >= min_bp) {
            MiniExpr *lambda = parse_lambda(p);
            if (!lambda)
                goto fail;
            if (!append(p, (void **)&left->as.call.args.items, &left->as.call.args.count,
                        sizeof(lambda), &lambda)) {
                mini_expr_free(lambda);
                goto fail;
            }
            left->end = lambda->end;
            continue;
        }
        if (p->current.kind == TOKEN_LESS && BP_POSTFIX >= min_bp &&
            (left->kind == MINI_EXPR_IDENTIFIER || left->kind == MINI_EXPR_MEMBER) &&
            !left->type_arg_count) {
            MiniParser trial = *p;
            MiniType **args = NULL;
            size_t count = 0;
            if (type_arguments(&trial, &args, &count) && trial.current.kind == TOKEN_LPAREN) {
                *p = trial;
                left->type_args = args;
                left->type_arg_count = count;
                left = parse_call(p, left);
                if (!left)
                    return NULL;
                continue;
            }
            for (size_t i = 0; i < count; i++)
                mini_type_free(args[i]);
            free(args);
        }
        if (p->allow_struct_literal && left->kind == MINI_EXPR_MEMBER) {
            MiniParser trial = *p;
            MiniType **args = NULL;
            size_t count = 0;
            bool literal = trial.current.kind == TOKEN_LBRACE;
            if (trial.current.kind == TOKEN_LESS)
                literal = type_arguments(&trial, &args, &count) &&
                          (trial.current.kind == TOKEN_LBRACE || trial.current.kind == TOKEN_DOT);
            if (literal) {
                MiniExpr *name = new_expr(p, MINI_EXPR_IDENTIFIER, left->start, left->end);
                if (!name) {
                    for (size_t i = 0; i < count; i++)
                        mini_type_free(args[i]);
                    free(args);
                    goto fail;
                }
                name->as.identifier =
                    (Token){TOKEN_IDENTIFIER, left->start, left->end - left->start};
                name->type_args = args;
                name->type_arg_count = count;
                mini_expr_free(left);
                *p = trial;
                left = p->current.kind == TOKEN_LBRACE ? parse_struct_literal(p, name) : name;
                if (!left)
                    return NULL;
                continue;
            }
            for (size_t i = 0; i < count; i++)
                mini_type_free(args[i]);
            free(args);
        }
        Token op = p->current;

        if (p->current.kind == TOKEN_SCOPE && BP_POSTFIX >= min_bp) {
            advance(p);
            if (left->type_arg_count || !type_arguments(p, &left->type_args, &left->type_arg_count))
                goto fail;
            if (p->current.kind != TOKEN_LPAREN) {
                error(p, "generic arguments require a call");
                goto fail;
            }
            continue;
        }

        if (BP_POSTFIX >= min_bp) {
            if (op.kind == TOKEN_LPAREN) {
                left = parse_call(p, left);
                if (!left)
                    return NULL;
                continue;
            }

            if (op.kind == TOKEN_LBRACKET) {
                advance(p);

                p->continuation++;
                MiniExpr *index = parse_bp(p, 0);
                p->continuation--;
                if (!index)
                    goto fail;

                if (!expect(p, TOKEN_RBRACKET)) {
                    mini_expr_free(index);
                    goto fail;
                }

                MiniExpr *expr = new_expr(p, MINI_EXPR_INDEX, left->start, token_end(p->previous));

                if (!expr) {
                    mini_expr_free(index);
                    goto fail;
                }

                expr->as.index.target = left;
                expr->as.index.index = index;
                left = expr;
                continue;
            }

            if (op.kind == TOKEN_DOT) {
                advance(p);

                Token name;
                if (!expect_identifier(p, &name))
                    goto fail;

                MiniExpr *expr = new_expr(p, MINI_EXPR_MEMBER, left->start, token_end(name));

                if (!expr)
                    goto fail;

                expr->as.member.target = left;
                expr->as.member.name = name;
                left = expr;
                continue;
            }
        }

        int lbp;
        int rbp;

        if (!infix_bp(op.kind, &lbp, &rbp) || lbp < min_bp) {
            break;
        }

        advance(p);

        MiniExpr *right = parse_bp(p, rbp);
        if (!right)
            goto fail;

        MiniExprKind kind = op.kind == TOKEN_EQUAL ? MINI_EXPR_ASSIGN : MINI_EXPR_BINARY;

        MiniExpr *expr = new_expr(p, kind, left->start, right->end);

        if (!expr) {
            mini_expr_free(right);
            goto fail;
        }

        if (kind == MINI_EXPR_ASSIGN) {
            expr->as.assign.target = left;
            expr->as.assign.value = right;
        } else {
            expr->as.binary.op = op;
            expr->as.binary.left = left;
            expr->as.binary.right = right;
        }

        left = expr;
    }

    if (p->has_error)
        goto fail;

    return left;

fail:
    mini_expr_free(left);
    return NULL;
}

MiniExpr *mini_parse_expression(MiniParser *p) {
    MiniExpr *expr = parse_bp(p, 0);

    if (p->has_error) {
        mini_expr_free(expr);
        return NULL;
    }

    return expr;
}

/* ------------------------------------------------------------
 * Types
 * ------------------------------------------------------------ */

static MiniType *mini_parse_type_inner(MiniParser *p);
MiniType *mini_parse_type(MiniParser *p) {
    if (++p->nesting > 256) {
        error(p, "syntax nesting limit exceeded");
        p->nesting--;
        return NULL;
    }
    MiniType *result = mini_parse_type_inner(p);
    p->nesting--;
    return result;
}
static MiniType *mini_parse_type_inner(MiniParser *p) {
    if (p->has_error)
        return NULL;

    Token token = p->current;
    MiniType *type = calloc(1, sizeof(*type));

    if (!type) {
        error(p, "out of memory");
        return NULL;
    }

    type->start = token.start;

    switch (token.kind) {
    case TOKEN_IDENTIFIER:
        type->kind = MINI_TYPE_NAMED;
        if (!qualified_name(p, &type->as.name))
            goto fail;
        if (p->current.kind == TOKEN_LESS && !type_arguments(p, &type->args, &type->arg_count))
            goto fail;
        break;

    case TOKEN_STAR:
        type->kind = MINI_TYPE_POINTER;
        advance(p);

        type->as.pointer.element = mini_parse_type(p);
        if (!type->as.pointer.element)
            goto fail;
        break;

    case TOKEN_LPAREN:
        type->kind = MINI_TYPE_FUNCTION;
        advance(p);
        while (!p->has_error && p->current.kind != TOKEN_RPAREN) {
            MiniType *param = mini_parse_type(p);
            if (!param)
                goto fail;
            if (!append(p, (void **)&type->args, &type->arg_count, sizeof(param), &param)) {
                mini_type_free(param);
                goto fail;
            }
            if (!accept(p, TOKEN_COMMA))
                break;
        }
        if (!expect(p, TOKEN_RPAREN) || !expect(p, TOKEN_ARROW))
            goto fail;
        type->as.function.result = mini_parse_type(p);
        if (!type->as.function.result)
            goto fail;
        break;

    case TOKEN_LBRACKET:
        type->kind = MINI_TYPE_ARRAY;
        advance(p);

        type->as.array.element = mini_parse_type(p);
        if (!type->as.array.element)
            goto fail;

        if (!expect(p, TOKEN_SEMICOLON))
            goto fail;

        if (p->current.kind != TOKEN_INTEGER) {
            error(p, "expected array length");
            goto fail;
        }

        type->as.array.length = p->current;
        advance(p);

        if (!expect(p, TOKEN_RBRACKET))
            goto fail;
        break;

    default:
        error(p, "expected type");
        goto fail;
    }

    if (p->has_error)
        goto fail;

    type->end = token_end(p->previous);
    return type;

fail:
    mini_type_free(type);
    return NULL;
}

/* ------------------------------------------------------------
 * Statements
 * ------------------------------------------------------------ */

static MiniExpr *parse_condition(MiniParser *p) {
    bool previous = p->allow_struct_literal;
    p->allow_struct_literal = false;

    MiniExpr *condition = parse_bp(p, 0);

    p->allow_struct_literal = previous;
    return condition;
}

static MiniStmt *parse_block_inner(MiniParser *p);
static MiniStmt *parse_block(MiniParser *p) {
    if (++p->nesting > 256) {
        error(p, "syntax nesting limit exceeded");
        p->nesting--;
        return NULL;
    }
    MiniStmt *result = parse_block_inner(p);
    p->nesting--;
    return result;
}
static MiniStmt *parse_block_inner(MiniParser *p) {
    size_t start = p->current.start;

    if (!expect(p, TOKEN_LBRACE))
        return NULL;

    MiniStmt *block = new_stmt(p, MINI_STMT_BLOCK, start);

    if (!block)
        return NULL;

    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        MiniStmt *stmt = parse_statement(p);
        if (!stmt)
            goto fail;

        if (!append(p, (void **)&block->as.block.items, &block->as.block.count, sizeof(MiniStmt *),
                    &stmt)) {
            mini_stmt_free(stmt);
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RBRACE))
        goto fail;

    finish_stmt(p, block);
    return block;

fail:
    mini_stmt_free(block);
    return NULL;
}

static MiniStmt *parse_let(MiniParser *p) {
    size_t start = p->current.start;
    bool immutable = p->current.kind == TOKEN_VAL;
    advance(p);
    MiniStmt *stmt = new_stmt(p, MINI_STMT_LET, start);
    if (!stmt)
        return NULL;
    stmt->as.let.immutable = immutable;
    if (!expect_identifier(p, &stmt->as.let.name))
        goto fail;
    if (accept(p, TOKEN_COLON)) {
        stmt->as.let.type = mini_parse_type(p);
        if (!stmt->as.let.type)
            goto fail;
    }
    if (accept(p, TOKEN_EQUAL)) {
        stmt->as.let.value = parse_bp(p, 0);
        if (!stmt->as.let.value)
            goto fail;
    }
    if (!stmt->as.let.type && !stmt->as.let.value) {
        error(p, "a local requires a type or initializer");
        goto fail;
    }
    if (!statement_end(p))
        goto fail;
    finish_stmt(p, stmt);
    return stmt;
fail:
    mini_stmt_free(stmt);
    return NULL;
}

static MiniStmt *parse_return(MiniParser *p) {
    size_t start = p->current.start;
    advance(p);

    MiniStmt *stmt = new_stmt(p, MINI_STMT_RETURN, start);

    if (!stmt)
        return NULL;

    if (p->current.kind != TOKEN_SEMICOLON && p->current.kind != TOKEN_RBRACE &&
        p->current.kind != TOKEN_EOF && !line_break(p)) {
        stmt->as.ret.value = parse_bp(p, 0);
        if (!stmt->as.ret.value)
            goto fail;
    }

    if (!statement_end(p))
        goto fail;

    finish_stmt(p, stmt);
    return stmt;

fail:
    mini_stmt_free(stmt);
    return NULL;
}

static MiniStmt *parse_if(MiniParser *p) {
    size_t start = p->current.start;
    advance(p);

    MiniStmt *stmt = new_stmt(p, MINI_STMT_IF, start);

    if (!stmt)
        return NULL;

    stmt->as.if_stmt.condition = parse_condition(p);
    if (!stmt->as.if_stmt.condition)
        goto fail;

    stmt->as.if_stmt.then_branch = parse_block(p);
    if (!stmt->as.if_stmt.then_branch)
        goto fail;

    if (accept(p, TOKEN_ELSE)) {
        if (p->current.kind == TOKEN_IF) {
            stmt->as.if_stmt.else_branch = parse_if(p);
        } else {
            stmt->as.if_stmt.else_branch = parse_block(p);
        }

        if (!stmt->as.if_stmt.else_branch)
            goto fail;
    }

    finish_stmt(p, stmt);
    return stmt;

fail:
    mini_stmt_free(stmt);
    return NULL;
}

static MiniStmt *parse_while(MiniParser *p) {
    size_t start = p->current.start;
    advance(p);

    MiniStmt *stmt = new_stmt(p, MINI_STMT_WHILE, start);

    if (!stmt)
        return NULL;

    stmt->as.while_stmt.condition = parse_condition(p);
    if (!stmt->as.while_stmt.condition)
        goto fail;

    stmt->as.while_stmt.body = parse_block(p);
    if (!stmt->as.while_stmt.body)
        goto fail;

    finish_stmt(p, stmt);
    return stmt;

fail:
    mini_stmt_free(stmt);
    return NULL;
}

static MiniStmt *parse_expr_stmt(MiniParser *p) {
    size_t start = p->current.start;

    MiniStmt *stmt = new_stmt(p, MINI_STMT_EXPR, start);

    if (!stmt)
        return NULL;

    stmt->as.expr = parse_bp(p, 0);
    if (!stmt->as.expr)
        goto fail;

    if (!statement_end(p))
        goto fail;

    finish_stmt(p, stmt);
    return stmt;

fail:
    mini_stmt_free(stmt);
    return NULL;
}

static MiniStmt *parse_statement(MiniParser *p) {
    if (p->has_error)
        return NULL;

    switch (p->current.kind) {
    case TOKEN_VAL:
    case TOKEN_VAR:
    case TOKEN_LET:
        return parse_let(p);

    case TOKEN_RETURN:
        return parse_return(p);

    case TOKEN_IF:
        return parse_if(p);

    case TOKEN_WHILE:
        return parse_while(p);

    case TOKEN_LBRACE:
        return parse_block(p);

    default:
        return parse_expr_stmt(p);
    }
}

/* ------------------------------------------------------------
 * Functions
 * ------------------------------------------------------------ */

static MiniDecl *parse_function(MiniParser *p, unsigned flags, bool in_interface) {
    bool is_extern = accept(p, TOKEN_EXTERN);

    accept(p, TOKEN_FN); // optional legacy introducer

    Token name;
    if (!expect_identifier(p, &name))
        return NULL;

    MiniDecl *decl = new_decl(p, MINI_DECL_FUNCTION, name);

    if (!decl)
        return NULL;

    decl->as.function.is_extern = is_extern;
    decl->flags = flags;
    if (!generic_parameters(p, decl))
        goto fail;

    if (!expect(p, TOKEN_LPAREN))
        goto fail;

    while (!p->has_error && p->current.kind != TOKEN_RPAREN && p->current.kind != TOKEN_EOF) {
        MiniParameter param = {0};

        if (!expect_identifier(p, &param.name))
            goto fail;

        if (!expect(p, TOKEN_COLON))
            goto fail;

        param.type = mini_parse_type(p);
        if (!param.type)
            goto fail;

        if (!append(p, (void **)&decl->as.function.params, &decl->as.function.param_count,
                    sizeof(param), &param)) {
            mini_type_free(param.type);
            goto fail;
        }

        if (accept(p, TOKEN_COMMA))
            continue;

        if (p->current.kind != TOKEN_RPAREN) {
            error(p, "expected ',' or ')'");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RPAREN))
        goto fail;

    if (accept(p, TOKEN_ARROW) || accept(p, TOKEN_COLON)) {
        decl->as.function.return_type = mini_parse_type(p);

        if (!decl->as.function.return_type)
            goto fail;
    }

    if (accept(p, TOKEN_EQUAL)) {
        if (is_extern || (flags & MINI_ABSTRACT)) {
            error(p, "extern/abstract methods cannot have expression bodies");
            goto fail;
        }
        if (!decl->as.function.return_type) {
            error(p, "expression bodies require an explicit return type");
            goto fail;
        }
        decl->as.function.expression_body = true;
        MiniStmt *body = new_stmt(p, MINI_STMT_RETURN, p->current.start);
        if (!body)
            goto fail;
        decl->as.function.body = body;
        body->as.ret.value = parse_bp(p, 0);
        if (!body->as.ret.value || !statement_end(p))
            goto fail;
        finish_stmt(p, body);
    } else if (is_extern || (flags & MINI_ABSTRACT) ||
               (in_interface &&
                (p->current.kind == TOKEN_SEMICOLON || p->current.kind == TOKEN_RBRACE ||
                 (line_break(p) && p->current.kind != TOKEN_LBRACE)))) {
        if (in_interface && !is_extern)
            decl->flags |= MINI_ABSTRACT;
        if (!statement_end(p))
            goto fail;
    } else {
        decl->as.function.body = parse_block(p);
        if (!decl->as.function.body)
            goto fail;
    }

    return decl;

fail:
    mini_decl_free(decl);
    return NULL;
}

/* ------------------------------------------------------------
 * Struct declarations
 * ------------------------------------------------------------ */

static MiniDecl *parse_struct(MiniParser *p, unsigned flags) {
    TokenKind kind = p->current.kind;
    advance(p);
    if (kind == TOKEN_ANNOTATION && !expect(p, TOKEN_CLASS))
        return NULL;

    Token name;
    if (!expect_identifier(p, &name))
        return NULL;

    MiniDecl *decl = new_decl(p, MINI_DECL_STRUCT, name);

    if (!decl)
        return NULL;

    decl->flags = flags;
    decl->as.struct_decl.is_class = kind == TOKEN_CLASS;
    decl->as.struct_decl.is_interface = kind == TOKEN_INTERFACE;
    decl->as.struct_decl.is_annotation = kind == TOKEN_ANNOTATION;
    if (!generic_parameters(p, decl))
        goto fail;
    if (accept(p, TOKEN_COLON)) {
        do {
            MiniType *base = mini_parse_type(p);
            if (!base)
                goto fail;
            if (!append(p, (void **)&decl->as.struct_decl.supers, &decl->as.struct_decl.super_count,
                        sizeof(base), &base)) {
                mini_type_free(base);
                goto fail;
            }
        } while (accept(p, TOKEN_COMMA));
    }
    if (!expect(p, TOKEN_LBRACE))
        goto fail;
    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        MiniAnnotation *member_annotations = NULL;
        size_t member_annotation_count = 0;
        if (!annotations(p, &member_annotations, &member_annotation_count)) {
            mini_annotations_free(member_annotations, member_annotation_count);
            goto fail;
        }
        unsigned member_flags = modifiers(p);
        Lexer lookahead = p->lexer;
        Token next = lexer_next(&lookahead);
        if (p->current.kind == TOKEN_EXTERN || p->current.kind == TOKEN_FN ||
            (p->current.kind == TOKEN_IDENTIFIER &&
             (next.kind == TOKEN_LPAREN || next.kind == TOKEN_LESS))) {
            MiniDecl *method = parse_function(p, member_flags, kind == TOKEN_INTERFACE);
            if (!method) {
                mini_annotations_free(member_annotations, member_annotation_count);
                goto fail;
            }
            method->annotations = member_annotations;
            method->annotation_count = member_annotation_count;
            if (!append(p, (void **)&decl->as.struct_decl.methods,
                        &decl->as.struct_decl.method_count, sizeof(method), &method)) {
                mini_decl_free(method);
                goto fail;
            }
            continue;
        }
        if (kind == TOKEN_INTERFACE) {
            error(p, "interfaces cannot store fields");
            mini_annotations_free(member_annotations, member_annotation_count);
            goto fail;
        }
        if (accept(p, TOKEN_VAL))
            member_flags |= MINI_IMMUTABLE;
        else
            accept(p, TOKEN_VAR);
        MiniParameter field = {.flags = member_flags,
                               .annotations = member_annotations,
                               .annotation_count = member_annotation_count};
        if (!expect_identifier(p, &field.name) || !expect(p, TOKEN_COLON) ||
            (field.type = mini_parse_type(p)) == NULL) {
            mini_annotations_free(member_annotations, member_annotation_count);
            goto fail;
        }
        if (!append(p, (void **)&decl->as.struct_decl.fields, &decl->as.struct_decl.field_count,
                    sizeof(field), &field)) {
            mini_type_free(field.type);
            mini_annotations_free(member_annotations, member_annotation_count);
            goto fail;
        }

        if (accept(p, TOKEN_COMMA) || accept(p, TOKEN_SEMICOLON))
            continue;

        if (p->current.kind != TOKEN_RBRACE && !line_break(p)) {
            error(p, "expected field separator or a line break");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RBRACE))
        goto fail;

    return decl;

fail:
    mini_decl_free(decl);
    return NULL;
}

/* ------------------------------------------------------------
 * Enum declarations
 * ------------------------------------------------------------ */

static MiniDecl *parse_enum(MiniParser *p) {
    advance(p); // enum

    Token name;
    if (!expect_identifier(p, &name))
        return NULL;

    MiniDecl *decl = new_decl(p, MINI_DECL_ENUM, name);

    if (!decl)
        return NULL;

    if (!expect(p, TOKEN_LBRACE))
        goto fail;

    while (!p->has_error && p->current.kind != TOKEN_RBRACE && p->current.kind != TOKEN_EOF) {
        Token variant;

        if (!expect_identifier(p, &variant))
            goto fail;

        if (!append(p, (void **)&decl->as.enum_decl.variants, &decl->as.enum_decl.variant_count,
                    sizeof(variant), &variant)) {
            goto fail;
        }

        if (accept(p, TOKEN_COMMA))
            continue;

        if (p->current.kind != TOKEN_RBRACE) {
            error(p, "expected ',' or '}'");
            goto fail;
        }
    }

    if (!expect(p, TOKEN_RBRACE))
        goto fail;

    return decl;

fail:
    mini_decl_free(decl);
    return NULL;
}

/* ------------------------------------------------------------
 * Complete program
 * ------------------------------------------------------------ */

MiniProgram *mini_parse_program(MiniParser *p) {
    if (p->has_error)
        return NULL;

    MiniProgram *program = calloc(1, sizeof(*program));

    if (!program) {
        error(p, "out of memory");
        return NULL;
    }

    if (accept(p, TOKEN_PACKAGE)) {
        if (!qualified_name(p, &program->package_name) || !statement_end(p))
            goto fail;
    }
    while (accept(p, TOKEN_IMPORT)) {
        MiniImport item = {0};
        if (!qualified_name(p, &item.path))
            goto fail;
        if (accept(p, TOKEN_DOT)) {
            if (!expect(p, TOKEN_STAR))
                goto fail;
            item.star = true;
        }
        if (accept(p, TOKEN_AS)) {
            if (item.star) {
                error(p, "star import cannot have an alias");
                goto fail;
            }
            if (!expect_identifier(p, &item.alias))
                goto fail;
        }
        if (!statement_end(p))
            goto fail;
        if (!append(p, (void **)&program->imports, &program->import_count, sizeof(item), &item))
            goto fail;
    }
    while (!p->has_error && p->current.kind != TOKEN_EOF) {
        MiniAnnotation *decl_annotations = NULL;
        size_t decl_annotation_count = 0;
        if (!annotations(p, &decl_annotations, &decl_annotation_count)) {
            mini_annotations_free(decl_annotations, decl_annotation_count);
            goto fail;
        }
        unsigned flags = modifiers(p);
        MiniDecl *decl = NULL;

        switch (p->current.kind) {
        case TOKEN_IDENTIFIER:
        case TOKEN_FN:
        case TOKEN_EXTERN:
            decl = parse_function(p, flags, false);
            break;

        case TOKEN_ANNOTATION:
        case TOKEN_CLASS:
        case TOKEN_INTERFACE:
        case TOKEN_STRUCT:
            decl = parse_struct(p, flags);
            break;

        case TOKEN_ENUM:
            decl = parse_enum(p);
            break;

        default:
            error(p, "expected top-level declaration");
            mini_annotations_free(decl_annotations, decl_annotation_count);
            goto fail;
        }

        if (!decl) {
            mini_annotations_free(decl_annotations, decl_annotation_count);
            goto fail;
        }
        decl->annotations = decl_annotations;
        decl->annotation_count = decl_annotation_count;
        decl->flags = flags;

        if (!append(p, (void **)&program->items, &program->count, sizeof(*program->items), &decl)) {
            mini_decl_free(decl);
            goto fail;
        }
    }

    if (p->has_error)
        goto fail;

    return program;

fail:
    mini_program_free(program);
    return NULL;
}
