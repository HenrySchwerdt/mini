#ifndef MINI_LEXER_H
#define MINI_LEXER_H

#include <stddef.h>
#include "mini/token.h"

typedef struct {
    const char *source;
    size_t length;
    size_t position;
} Lexer;

Lexer lexer_new(const char *source, size_t length);
Token lexer_next(Lexer *lexer);

#endif

