
#include <unity/unity.h>
#include "mini/lexer.h"

#include <string.h>

void setUp(void) {}
void tearDown(void) {}

static void assert_tokens(
    const char *source,
    const TokenKind *expected,
    size_t count
) {
    Lexer lexer = lexer_new(source, strlen(source));

    for (size_t i = 0; i < count; i++) {
        Token token = lexer_next(&lexer);

        TEST_ASSERT_EQUAL_INT(
            expected[i],
            token.kind
        );
    }

    TEST_ASSERT_EQUAL_INT(
        TOKEN_EOF,
        lexer_next(&lexer).kind
    );
}

#define ASSERT_TOKENS(source, ...) do {               \
    const TokenKind kinds[] = { __VA_ARGS__ };         \
    assert_tokens(                                     \
        source,                                        \
        kinds,                                         \
        sizeof(kinds) / sizeof(kinds[0])               \
    );                                                \
} while (0)

void test_keywords(void) {
    ASSERT_TOKENS(
        "fn let return if else while match "
        "struct enum extern true false",
        TOKEN_FN, TOKEN_LET, TOKEN_RETURN,
        TOKEN_IF, TOKEN_ELSE, TOKEN_WHILE,
        TOKEN_MATCH, TOKEN_STRUCT, TOKEN_ENUM,
        TOKEN_EXTERN, TOKEN_TRUE, TOKEN_FALSE
    );
}

void test_operators(void) {
    ASSERT_TOKENS(
        "+ - * / % = == ! != < <= > >= "
        "& && || -> =>",
        TOKEN_PLUS, TOKEN_MINUS, TOKEN_STAR,
        TOKEN_SLASH, TOKEN_PERCENT,
        TOKEN_EQUAL, TOKEN_EQUAL_EQUAL,
        TOKEN_BANG, TOKEN_BANG_EQUAL,
        TOKEN_LESS, TOKEN_LESS_EQUAL,
        TOKEN_GREATER, TOKEN_GREATER_EQUAL,
        TOKEN_AMP, TOKEN_AMP_AMP,
        TOKEN_PIPE_PIPE,
        TOKEN_ARROW, TOKEN_FAT_ARROW
    );
}

void test_punctuation(void) {
    ASSERT_TOKENS(
        "(){}[],:;.@::",
        TOKEN_LPAREN, TOKEN_RPAREN,
        TOKEN_LBRACE, TOKEN_RBRACE,
        TOKEN_LBRACKET, TOKEN_RBRACKET,
        TOKEN_COMMA, TOKEN_COLON,
        TOKEN_SEMICOLON, TOKEN_DOT, TOKEN_AT, TOKEN_SCOPE
    );
}

void test_identifiers(void) {
    ASSERT_TOKENS(
        "_ _value value123 fn_test",
        TOKEN_UNDERSCORE,
        TOKEN_IDENTIFIER,
        TOKEN_IDENTIFIER,
        TOKEN_IDENTIFIER
    );
}

void test_literals(void) {
    ASSERT_TOKENS(
        "42 123 \"hello\" \"hello\\nworld\"",
        TOKEN_INTEGER,
        TOKEN_INTEGER,
        TOKEN_STRING,
        TOKEN_STRING
    );
}

void test_comments(void) {
    ASSERT_TOKENS(
        "fn // comment\n"
        "main /* comment */ ()",
        TOKEN_FN,
        TOKEN_IDENTIFIER,
        TOKEN_LPAREN,
        TOKEN_RPAREN
    );
}

void test_invalid_tokens(void) {
    ASSERT_TOKENS(
        "$ |",
        TOKEN_ERROR,
        TOKEN_ERROR
    );
}

void test_unterminated_string(void) {
    Lexer lexer = lexer_new("\"hello", 6);

    TEST_ASSERT_EQUAL(
        TOKEN_ERROR,
        lexer_next(&lexer).kind
    );
}

void test_unterminated_comment(void) {
    const char *source = "/* hello";
    Lexer lexer = lexer_new(source, strlen(source));

    TEST_ASSERT_EQUAL(
        TOKEN_ERROR,
        lexer_next(&lexer).kind
    );
}

void test_complete_function(void) {
    ASSERT_TOKENS(
        "fn main() -> i32 { return 42; }",
        TOKEN_FN,
        TOKEN_IDENTIFIER,
        TOKEN_LPAREN,
        TOKEN_RPAREN,
        TOKEN_ARROW,
        TOKEN_IDENTIFIER,
        TOKEN_LBRACE,
        TOKEN_RETURN,
        TOKEN_INTEGER,
        TOKEN_SEMICOLON,
        TOKEN_RBRACE
    );
}

int main(void) {
    UNITY_BEGIN();

    RUN_TEST(test_keywords);
    RUN_TEST(test_operators);
    RUN_TEST(test_punctuation);
    RUN_TEST(test_identifiers);
    RUN_TEST(test_literals);
    RUN_TEST(test_comments);
    RUN_TEST(test_invalid_tokens);
    RUN_TEST(test_unterminated_string);
    RUN_TEST(test_unterminated_comment);
    RUN_TEST(test_complete_function);

    return UNITY_END();
}
