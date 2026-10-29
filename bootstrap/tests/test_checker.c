#include <unity/unity.h>

#include "mini/parser.h"
#include "mini/checker.h"

#include <string.h>

/*
 * Parse and typecheck a complete Mini program.
 *
 * All allocated resources are released,
 * including when semantic analysis fails.
 */
static MiniCheckResult check_source(const char *source) {
    size_t length = strlen(source);

    MiniParser parser = mini_parser_new(source, length);
    MiniProgram *ast = mini_parse_program(&parser);

    TEST_ASSERT_FALSE_MESSAGE(
        parser.has_error,
        parser.error_message
            ? parser.error_message
            : "parser failed"
    );

    TEST_ASSERT_NOT_NULL(ast);

    MiniChecker *checker = mini_checker_create();
    TEST_ASSERT_NOT_NULL(checker);

    MiniCheckedProgram *checked = NULL;

    MiniCheckResult result = mini_checker_check(
        checker,
        ast,
        source,
        length,
        &checked
    );

    if (result.status == MINI_CHECK_OK) {
        TEST_ASSERT_NOT_NULL(checked);
        TEST_ASSERT_EQUAL_PTR(
            ast,
            mini_checked_ast(checked)
        );
    } else {
        TEST_ASSERT_NULL(checked);
        TEST_ASSERT_NOT_NULL(result.message);
    }

    mini_checked_program_destroy(checked);
    mini_checker_destroy(checker);
    mini_program_free(ast);

    return result;
}

#define ASSERT_VALID(source) do {                       \
    MiniCheckResult result = check_source(source);      \
    TEST_ASSERT_EQUAL_MESSAGE(                          \
        MINI_CHECK_OK,                                  \
        result.status,                                  \
        result.message ? result.message : "check failed"\
    );                                                   \
} while (0)

#define ASSERT_INVALID(source) do {                     \
    MiniCheckResult result = check_source(source);      \
    TEST_ASSERT_EQUAL(MINI_CHECK_TYPE_ERROR, result.status); \
} while (0)

void setUp(void) {}
void tearDown(void) {}

/* ------------------------------------------------------------
 * Primitive types
 * ------------------------------------------------------------ */

void test_integer_arithmetic(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 42;"
        "    let y: i32 = 10;"
        "    return x + y * 2;"
        "}"
    );
}

void test_boolean_expressions(void) {
    ASSERT_VALID(
        "fn main() -> bool {"
        "    let x: bool = true;"
        "    let y: bool = false;"
        "    return x && !y;"
        "}"
    );
}

void test_invalid_arithmetic(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    let x: bool = true;"
        "    return x + 1;"
        "}"
    );
}

void test_initializer_type_mismatch(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    let x: bool = 42;"
        "    return 0;"
        "}"
    );
}

void test_return_type_mismatch(void) {
    ASSERT_INVALID(
        "fn main() -> bool {"
        "    return 42;"
        "}"
    );
}

void test_integer_overflow(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    return 2147483648;"
        "}"
    );
}

/* ------------------------------------------------------------
 * Variables and scopes
 * ------------------------------------------------------------ */

void test_variable_assignment(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 10;"
        "    x = 20;"
        "    return x;"
        "}"
    );
}

void test_scope_shadowing(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 10;"
        "    {"
        "        let x: i32 = 20;"
        "        x = 30;"
        "    }"
        "    return x;"
        "}"
    );
}

void test_unknown_variable(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    return missing;"
        "}"
    );
}

void test_duplicate_variable(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    let x: i32 = 10;"
        "    let x: i32 = 20;"
        "    return x;"
        "}"
    );
}

void test_scope_visibility(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    { let x: i32 = 10; }"
        "    return x;"
        "}"
    );
}

void test_invalid_assignment_target(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    42 = 10;"
        "    return 0;"
        "}"
    );
}

/* ------------------------------------------------------------
 * Functions
 * ------------------------------------------------------------ */

void test_function_call(void) {
    ASSERT_VALID(
        "fn add(a: i32, b: i32) -> i32 {"
        "    return a + b;"
        "}"
        "fn main() -> i32 {"
        "    return add(10, 20);"
        "}"
    );
}

void test_forward_function_reference(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    return add(10, 20);"
        "}"
        "fn add(a: i32, b: i32) -> i32 {"
        "    return a + b;"
        "}"
    );
}

void test_recursive_function(void) {
    ASSERT_VALID(
        "fn factorial(n: i32) -> i32 {"
        "    if n <= 1 { return 1; }"
        "    return n * factorial(n - 1);"
        "}"
    );
}

void test_extern_function(void) {
    ASSERT_VALID(
        "extern fn print_i32(value: i32);"
        "fn main() -> i32 {"
        "    print_i32(42);"
        "    return 0;"
        "}"
    );
}

void test_wrong_argument_count(void) {
    ASSERT_INVALID(
        "fn add(a: i32, b: i32) -> i32 {"
        "    return a + b;"
        "}"
        "fn main() -> i32 {"
        "    return add(10);"
        "}"
    );
}

void test_wrong_argument_type(void) {
    ASSERT_INVALID(
        "fn identity(x: i32) -> i32 {"
        "    return x;"
        "}"
        "fn main() -> i32 {"
        "    return identity(true);"
        "}"
    );
}

void test_duplicate_function(void) {
    ASSERT_INVALID(
        "fn foo() -> i32 { return 1; }"
        "fn foo() -> i32 { return 2; }"
    );
}

void test_duplicate_parameter(void) {
    ASSERT_INVALID(
        "fn foo(x: i32, x: i32) -> i32 {"
        "    return x;"
        "}"
    );
}

/* ------------------------------------------------------------
 * Control flow
 * ------------------------------------------------------------ */

void test_if_else(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 10;"
        "    if x > 5 {"
        "        return 1;"
        "    } else {"
        "        return 0;"
        "    }"
        "}"
    );
}

void test_while_loop(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let i: i32 = 0;"
        "    while i < 10 {"
        "        i = i + 1;"
        "    }"
        "    return i;"
        "}"
    );
}

void test_invalid_if_condition(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    if 42 { return 1; }"
        "    return 0;"
        "}"
    );
}

/* ------------------------------------------------------------
 * Structs
 * ------------------------------------------------------------ */

void test_struct_literal_and_member(void) {
    ASSERT_VALID(
        "struct Point { x: i32, y: i32 }"
        "fn main() -> i32 {"
        "    let p: Point = Point { x: 10, y: 20 };"
        "    return p.x + p.y;"
        "}"
    );
}

void test_struct_field_assignment(void) {
    ASSERT_VALID(
        "struct Point { x: i32, y: i32 }"
        "fn main() -> i32 {"
        "    let p: Point = Point { x: 1, y: 2 };"
        "    p.x = 42;"
        "    return p.x;"
        "}"
    );
}

void test_missing_struct_field(void) {
    ASSERT_INVALID(
        "struct Point { x: i32, y: i32 }"
        "fn main() -> i32 {"
        "    let p: Point = Point { x: 10 };"
        "    return 0;"
        "}"
    );
}

void test_duplicate_struct_field(void) {
    ASSERT_INVALID(
        "struct Point { x: i32, y: i32 }"
        "fn main() -> i32 {"
        "    let p: Point = Point { x: 1, x: 2 };"
        "    return 0;"
        "}"
    );
}

void test_unknown_struct_field(void) {
    ASSERT_INVALID(
        "struct Point { x: i32 }"
        "fn main() -> i32 {"
        "    let p: Point = Point { x: 1 };"
        "    return p.missing;"
        "}"
    );
}

/* ------------------------------------------------------------
 * Pointers and arrays
 * ------------------------------------------------------------ */

void test_pointer_address_and_deref(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 42;"
        "    let p: *i32 = &x;"
        "    return *p;"
        "}"
    );
}

void test_pointer_assignment(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 10;"
        "    let p: *i32 = &x;"
        "    *p = 42;"
        "    return x;"
        "}"
    );
}

void test_array_type(void) {
    ASSERT_VALID(
        "fn first(items: [i32; 4]) -> i32 {"
        "    return items[0];"
        "}"
    );
}

void test_invalid_dereference(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    let x: i32 = 42;"
        "    return *x;"
        "}"
    );
}

void test_invalid_index(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    let x: i32 = 42;"
        "    return x[0];"
        "}"
    );
}

/* ------------------------------------------------------------
 * Enums and match
 * ------------------------------------------------------------ */

void test_enum_variant(void) {
    ASSERT_VALID(
        "enum Color { Red, Green, Blue }"
        "fn main() -> Color {"
        "    return Color.Red;"
        "}"
    );
}

void test_match_integer(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    let x: i32 = 2;"
        "    return match x {"
        "        1 => 10,"
        "        2 => 20,"
        "        _ => 0"
        "    };"
        "}"
    );
}

void test_match_boolean_exhaustive(void) {
    ASSERT_VALID(
        "fn main() -> i32 {"
        "    return match true {"
        "        true => 1,"
        "        false => 0"
        "    };"
        "}"
    );
}

void test_match_enum_exhaustive(void) {
    ASSERT_VALID(
        "enum Color { Red, Green }"
        "fn main() -> i32 {"
        "    let c: Color = Color.Red;"
        "    return match c {"
        "        Color.Red => 1,"
        "        Color.Green => 2"
        "    };"
        "}"
    );
}

void test_match_non_exhaustive(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    return match true {"
        "        true => 1"
        "    };"
        "}"
    );
}

void test_match_duplicate_pattern(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    return match 1 {"
        "        1 => 10,"
        "        1 => 20,"
        "        _ => 0"
        "    };"
        "}"
    );
}

void test_match_arm_type_mismatch(void) {
    ASSERT_INVALID(
        "fn main() -> i32 {"
        "    return match true {"
        "        true => 42,"
        "        false => false"
        "    };"
        "}"
    );
}

/* ------------------------------------------------------------
 * Type declarations and errors
 * ------------------------------------------------------------ */

void test_unknown_type(void) {
    ASSERT_INVALID(
        "fn main() -> Unknown {"
        "    return 0;"
        "}"
    );
}

void test_duplicate_enum_variant(void) {
    ASSERT_INVALID(
        "enum Color { Red, Red }"
    );
}

void test_duplicate_struct_declaration_field(void) {
    ASSERT_INVALID(
        "struct Point { x: i32, x: i32 }"
    );
}

/* ------------------------------------------------------------
 * Test runner
 * ------------------------------------------------------------ */

int main(void) {
    UNITY_BEGIN();

    RUN_TEST(test_integer_arithmetic);
    RUN_TEST(test_boolean_expressions);
    RUN_TEST(test_invalid_arithmetic);
    RUN_TEST(test_initializer_type_mismatch);
    RUN_TEST(test_return_type_mismatch);
    RUN_TEST(test_integer_overflow);

    RUN_TEST(test_variable_assignment);
    RUN_TEST(test_scope_shadowing);
    RUN_TEST(test_unknown_variable);
    RUN_TEST(test_duplicate_variable);
    RUN_TEST(test_scope_visibility);
    RUN_TEST(test_invalid_assignment_target);

    RUN_TEST(test_function_call);
    RUN_TEST(test_forward_function_reference);
    RUN_TEST(test_recursive_function);
    RUN_TEST(test_extern_function);
    RUN_TEST(test_wrong_argument_count);
    RUN_TEST(test_wrong_argument_type);
    RUN_TEST(test_duplicate_function);
    RUN_TEST(test_duplicate_parameter);

    RUN_TEST(test_if_else);
    RUN_TEST(test_while_loop);
    RUN_TEST(test_invalid_if_condition);

    RUN_TEST(test_struct_literal_and_member);
    RUN_TEST(test_struct_field_assignment);
    RUN_TEST(test_missing_struct_field);
    RUN_TEST(test_duplicate_struct_field);
    RUN_TEST(test_unknown_struct_field);

    RUN_TEST(test_pointer_address_and_deref);
    RUN_TEST(test_pointer_assignment);
    RUN_TEST(test_array_type);
    RUN_TEST(test_invalid_dereference);
    RUN_TEST(test_invalid_index);

    RUN_TEST(test_enum_variant);
    RUN_TEST(test_match_integer);
    RUN_TEST(test_match_boolean_exhaustive);
    RUN_TEST(test_match_enum_exhaustive);
    RUN_TEST(test_match_non_exhaustive);
    RUN_TEST(test_match_duplicate_pattern);
    RUN_TEST(test_match_arm_type_mismatch);

    RUN_TEST(test_unknown_type);
    RUN_TEST(test_duplicate_enum_variant);
    RUN_TEST(test_duplicate_struct_declaration_field);

    return UNITY_END();
}
