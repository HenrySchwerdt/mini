#ifndef MINI_PARSER_H
#define MINI_PARSER_H

#include <stdbool.h>
#include <stddef.h>

#include "mini/lexer.h"
#include "mini/ast.h"

typedef struct {
    Lexer lexer;

    Token current;
    Token previous;

    bool allow_struct_literal;

    size_t nesting;
    size_t continuation;
    bool has_error;
    Token error_token;
    const char *error_message;
} MiniParser;

/*
 * Initialize a parser.
 *
 * The source buffer must remain valid for the
 * entire lifetime of the parser and its AST.
 */
MiniParser mini_parser_new(
    const char *source,
    size_t length
);

/*
 * Parse a single expression.
 *
 * The returned AST belongs to the caller.
 * Free it using mini_expr_free().
 *
 * Does not require EOF after the expression.
 */
MiniExpr *mini_parse_expression(
    MiniParser *parser
);

/*
 * Parse a type expression.
 *
 * Examples:
 *   i32
 *   *i32
 *   [u8; 256]
 *
 * Free using mini_type_free().
 */
MiniType *mini_parse_type(
    MiniParser *parser
);

/*
 * Parse an entire source file.
 *
 * Supports:
 *   fn
 *   extern fn
 *   struct
 *   enum
 *
 * Returns NULL on failure.
 * Free using mini_program_free().
 */
MiniProgram *mini_parse_program(
    MiniParser *parser
);

/*
 * Check whether parsing encountered an error.
 */
bool mini_parser_has_error(
    const MiniParser *parser
);

#endif
