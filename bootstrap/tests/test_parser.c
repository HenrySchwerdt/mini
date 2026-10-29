
#include <unity/unity.h>
#include "mini/parser.h"

#include <string.h>

void setUp(void) {}
void tearDown(void) {}

static MiniExpr *parse(
    MiniParser *parser,
    const char *source
) {
    *parser = mini_parser_new(source, strlen(source));

    MiniExpr *expr = mini_parse_expression(parser);

    TEST_ASSERT_FALSE(parser->has_error);
    TEST_ASSERT_NOT_NULL(expr);
    TEST_ASSERT_EQUAL(TOKEN_EOF, parser->current.kind);

    return expr;
}

void test_operator_precedence(void) {
    MiniParser parser;
    MiniExpr *expr = parse(&parser, "2 + 3 * 4");

    TEST_ASSERT_EQUAL(MINI_EXPR_BINARY, expr->kind);
    TEST_ASSERT_EQUAL(TOKEN_PLUS, expr->as.binary.op.kind);

    MiniExpr *right = expr->as.binary.right;

    TEST_ASSERT_EQUAL(MINI_EXPR_BINARY, right->kind);
    TEST_ASSERT_EQUAL(TOKEN_STAR, right->as.binary.op.kind);

    mini_expr_free(expr);
}

void test_left_associativity(void) {
    MiniParser parser;
    MiniExpr *expr = parse(&parser, "10 - 5 - 2");

    TEST_ASSERT_EQUAL(TOKEN_MINUS, expr->as.binary.op.kind);
    TEST_ASSERT_EQUAL(
        MINI_EXPR_BINARY,
        expr->as.binary.left->kind
    );

    mini_expr_free(expr);
}

void test_assignment_associativity(void) {
    MiniParser parser;
    MiniExpr *expr = parse(&parser, "a = b = 42");

    TEST_ASSERT_EQUAL(MINI_EXPR_ASSIGN, expr->kind);
    TEST_ASSERT_EQUAL(
        MINI_EXPR_ASSIGN,
        expr->as.assign.value->kind
    );

    mini_expr_free(expr);
}

void test_function_call(void) {
    MiniParser parser;
    MiniExpr *expr = parse(&parser, "print(42, 10)");

    TEST_ASSERT_EQUAL(MINI_EXPR_CALL, expr->kind);
    TEST_ASSERT_EQUAL_size_t(2, expr->as.call.args.count);

    mini_expr_free(expr);
}

void test_member_and_index(void) {
    MiniParser parser;
    MiniExpr *expr = parse(&parser, "items[0].value");

    TEST_ASSERT_EQUAL(MINI_EXPR_MEMBER, expr->kind);
    TEST_ASSERT_EQUAL(
        MINI_EXPR_INDEX,
        expr->as.member.target->kind
    );

    mini_expr_free(expr);
}

void test_match_expression(void) {
    MiniParser parser;

    MiniExpr *expr = parse(
        &parser,
        "match value { 1 => 10, 2 => 20, _ => 0 }"
    );

    TEST_ASSERT_EQUAL(MINI_EXPR_MATCH, expr->kind);
    TEST_ASSERT_EQUAL_size_t(3, expr->as.match.arm_count);

    mini_expr_free(expr);
}

void test_invalid_expression(void) {
    const char *source = "2 +";

    MiniParser parser = mini_parser_new(
        source, strlen(source)
    );

    MiniExpr *expr = mini_parse_expression(&parser);

    TEST_ASSERT_NULL(expr);
    TEST_ASSERT_TRUE(parser.has_error);
}

int main(void) {
    UNITY_BEGIN();

    RUN_TEST(test_operator_precedence);
    RUN_TEST(test_left_associativity);
    RUN_TEST(test_assignment_associativity);
    RUN_TEST(test_function_call);
    RUN_TEST(test_member_and_index);
    RUN_TEST(test_match_expression);
    RUN_TEST(test_invalid_expression);

    return UNITY_END();
}
