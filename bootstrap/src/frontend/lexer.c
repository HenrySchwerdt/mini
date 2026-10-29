#include "mini/lexer.h"

#include <stdbool.h>
#include <string.h>

static bool at_end(const Lexer *lexer) {
    return lexer->position >= lexer->length;
}

static char peek(const Lexer *lexer) {
    return at_end(lexer) ? '\0' : lexer->source[lexer->position];
}

static char peek_next(const Lexer *lexer) {
    return lexer->position + 1 >= lexer->length ? '\0' : lexer->source[lexer->position + 1];
}

static char advance(Lexer *lexer) {
    return lexer->source[lexer->position++];
}

static bool match(Lexer *lexer, char expected) {
    if (at_end(lexer) || peek(lexer) != expected)
        return false;

    advance(lexer);
    return true;
}

static bool is_alpha(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
}

static bool is_digit(char c) {
    return c >= '0' && c <= '9';
}

static bool is_alnum(char c) {
    return is_alpha(c) || is_digit(c);
}

static bool is_space(char c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n';
}

static Token make_token(TokenKind kind, size_t start, size_t end) {
    return (Token){.kind = kind, .start = start, .length = end - start};
}

/* Consume `expected` if present, then build the token. The match must be sequenced before the
 * end position is read: as call arguments their evaluation order is unspecified (GCC evaluates
 * right to left), which truncated two-character tokens such as `!=` to `!`. */
static Token make_token_if(Lexer *lexer, size_t start, char expected, TokenKind yes, TokenKind no) {
    bool matched = match(lexer, expected);
    return make_token(matched ? yes : no, start, lexer->position);
}

static bool keyword(const char *text, size_t length, const char *expected) {
    size_t expected_length = strlen(expected);

    return length == expected_length && memcmp(text, expected, length) == 0;
}

static TokenKind identifier_kind(const char *text, size_t length) {
    if (length == 1 && text[0] == '_')
        return TOKEN_UNDERSCORE;

    struct {
        const char *name;
        TokenKind kind;
    } keywords[] = {{"annotation", TOKEN_ANNOTATION},
                    {"static", TOKEN_STATIC},
                    {"class", TOKEN_CLASS},
                    {"interface", TOKEN_INTERFACE},
                    {"package", TOKEN_PACKAGE},
                    {"import", TOKEN_IMPORT},
                    {"as", TOKEN_AS},
                    {"open", TOKEN_OPEN},
                    {"abstract", TOKEN_ABSTRACT},
                    {"override", TOKEN_OVERRIDE},
                    {"final", TOKEN_FINAL},
                    {"public", TOKEN_PUBLIC},
                    {"private", TOKEN_PRIVATE},
                    {"protected", TOKEN_PROTECTED},
                    {"internal", TOKEN_INTERNAL},
                    {"fn", TOKEN_FN},
                    {"let", TOKEN_LET},
                    {"val", TOKEN_VAL},
                    {"var", TOKEN_VAR},
                    {"return", TOKEN_RETURN},
                    {"if", TOKEN_IF},
                    {"else", TOKEN_ELSE},
                    {"while", TOKEN_WHILE},
                    {"match", TOKEN_MATCH},
                    {"struct", TOKEN_STRUCT},
                    {"enum", TOKEN_ENUM},
                    {"extern", TOKEN_EXTERN},
                    {"true", TOKEN_TRUE},
                    {"false", TOKEN_FALSE}};

    for (size_t i = 0; i < sizeof(keywords) / sizeof(keywords[0]); i++) {
        if (keyword(text, length, keywords[i].name))
            return keywords[i].kind;
    }

    return TOKEN_IDENTIFIER;
}

static Token scan_identifier(Lexer *lexer, size_t start) {
    while (!at_end(lexer) && is_alnum(peek(lexer)))
        advance(lexer);

    size_t length = lexer->position - start;

    TokenKind kind = identifier_kind(lexer->source + start, length);

    return make_token(kind, start, lexer->position);
}

static Token scan_number(Lexer *lexer, size_t start) {
    while (!at_end(lexer) && is_digit(peek(lexer)))
        advance(lexer);

    return make_token(TOKEN_INTEGER, start, lexer->position);
}

static Token scan_string(Lexer *lexer, size_t start) {
    while (!at_end(lexer)) {
        char c = advance(lexer);

        if (c == '"')
            return make_token(TOKEN_STRING, start, lexer->position);

        if (c == '\n' || c == '\r')
            return make_token(TOKEN_ERROR, start, lexer->position);

        if (c == '\\') {
            if (at_end(lexer))
                break;

            char escaped = advance(lexer);

            switch (escaped) {
            case 'n':
            case 'r':
            case 't':
            case '0':
            case '"':
            case '\\':
                break;
            default:
                return make_token(TOKEN_ERROR, start, lexer->position);
            }
        }
    }

    return make_token(TOKEN_ERROR, start, lexer->position);
}

static bool skip_whitespace_and_comments(Lexer *lexer) {
    for (;;) {
        while (!at_end(lexer) && is_space(peek(lexer)))
            advance(lexer);

        if (at_end(lexer))
            return true;

        if (peek(lexer) == '/' && peek_next(lexer) == '/') {
            advance(lexer);
            advance(lexer);

            while (!at_end(lexer) && peek(lexer) != '\n')
                advance(lexer);

            continue;
        }

        if (peek(lexer) == '/' && peek_next(lexer) == '*') {
            advance(lexer);
            advance(lexer);

            bool closed = false;

            while (!at_end(lexer)) {
                if (peek(lexer) == '*' && peek_next(lexer) == '/') {
                    advance(lexer);
                    advance(lexer);
                    closed = true;
                    break;
                }

                advance(lexer);
            }

            if (!closed)
                return false;

            continue;
        }

        return true;
    }
}

Lexer lexer_new(const char *source, size_t length) {
    return (Lexer){.source = source, .length = length, .position = 0};
}

Token lexer_next(Lexer *lexer) {
    size_t trivia_start = lexer->position;

    if (!skip_whitespace_and_comments(lexer)) {
        return make_token(TOKEN_ERROR, trivia_start, lexer->position);
    }

    size_t start = lexer->position;

    if (at_end(lexer))
        return make_token(TOKEN_EOF, start, start);

    char c = advance(lexer);

    if (is_alpha(c))
        return scan_identifier(lexer, start);

    if (is_digit(c))
        return scan_number(lexer, start);

    switch (c) {
    case '@':
        return make_token(TOKEN_AT, start, lexer->position);
    case '"':
        return scan_string(lexer, start);

    case '(':
        return make_token(TOKEN_LPAREN, start, lexer->position);
    case ')':
        return make_token(TOKEN_RPAREN, start, lexer->position);
    case '{':
        return make_token(TOKEN_LBRACE, start, lexer->position);
    case '}':
        return make_token(TOKEN_RBRACE, start, lexer->position);
    case '[':
        return make_token(TOKEN_LBRACKET, start, lexer->position);
    case ']':
        return make_token(TOKEN_RBRACKET, start, lexer->position);
    case ',':
        return make_token(TOKEN_COMMA, start, lexer->position);
    case ';':
        return make_token(TOKEN_SEMICOLON, start, lexer->position);
    case ':':
        return make_token_if(lexer, start, ':', TOKEN_SCOPE, TOKEN_COLON);
    case '.':
        return make_token(TOKEN_DOT, start, lexer->position);

    case '+':
        return make_token(TOKEN_PLUS, start, lexer->position);
    case '*':
        return make_token(TOKEN_STAR, start, lexer->position);
    case '/':
        return make_token(TOKEN_SLASH, start, lexer->position);
    case '%':
        return make_token(TOKEN_PERCENT, start, lexer->position);

    case '-':
        return make_token_if(lexer, start, '>', TOKEN_ARROW, TOKEN_MINUS);

    case '=':
        if (match(lexer, '='))
            return make_token(TOKEN_EQUAL_EQUAL, start, lexer->position);
        return make_token_if(lexer, start, '>', TOKEN_FAT_ARROW, TOKEN_EQUAL);

    case '!':
        return make_token_if(lexer, start, '=', TOKEN_BANG_EQUAL, TOKEN_BANG);

    case '<':
        return make_token_if(lexer, start, '=', TOKEN_LESS_EQUAL, TOKEN_LESS);

    case '>':
        return make_token_if(lexer, start, '=', TOKEN_GREATER_EQUAL, TOKEN_GREATER);

    case '&':
        return make_token_if(lexer, start, '&', TOKEN_AMP_AMP, TOKEN_AMP);

    case '|':
        if (match(lexer, '|'))
            return make_token(TOKEN_PIPE_PIPE, start, lexer->position);
        break;
    }

    return make_token(TOKEN_ERROR, start, lexer->position);
}
