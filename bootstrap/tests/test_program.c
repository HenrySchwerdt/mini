
#include <unity/unity.h>
#include "mini/parser.h"

#include <string.h>

void setUp(void) {}
void tearDown(void) {}

void test_complete_program(void) {
    const char *source =
        "fn main() -> i32 {"
        "    let x: i32 = 42;"
        "    if x > 10 {"
        "        return x;"
        "    } else {"
        "        return 0;"
        "    }"
        "}";

    MiniParser parser = mini_parser_new(
        source, strlen(source)
    );

    MiniProgram *program = mini_parse_program(&parser);

    TEST_ASSERT_FALSE(parser.has_error);
    TEST_ASSERT_NOT_NULL(program);
    TEST_ASSERT_EQUAL_size_t(1, program->count);
    TEST_ASSERT_EQUAL(
        MINI_DECL_FUNCTION,
        program->items[0]->kind
    );

    mini_program_free(program);
}

void test_invalid_program(void) {
    const char *source =
        "fn main() -> i32 { return ;";

    MiniParser parser = mini_parser_new(
        source, strlen(source)
    );

    MiniProgram *program = mini_parse_program(&parser);

    TEST_ASSERT_NULL(program);
    TEST_ASSERT_TRUE(parser.has_error);
}

int main(void) {
    UNITY_BEGIN();

    RUN_TEST(test_complete_program);
    RUN_TEST(test_invalid_program);

    return UNITY_END();
}
