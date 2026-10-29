#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static void valid(const char *text) {
    MiniSource source = {"syntax.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    TEST_ASSERT_TRUE_MESSAGE(r.c_source != NULL, r.message);
    mini_compile_result_destroy(&r);
}
static void invalid(const char *text, const char *reason) {
    MiniSource source = {"syntax.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    TEST_ASSERT_FALSE_MESSAGE(r.c_source, "invalid syntax was accepted");
    TEST_ASSERT_NOT_NULL(r.message);
    TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, reason), r.message);
    mini_compile_result_destroy(&r);
}
static void test_target_syntax(void) {
    valid("class Main {\n static answer(): Result<i32, bool> = Result.Ok(42)\n"
          "static main(): i32 {\n val result = Main.answer()\n val value = match result {\n"
          "Ok(value) => value,\n Err(error) => 0,\n }\n return value\n }\n}");
}
static void test_inferred_var(void) {
    valid(
        "class Main { static main(): i32 {\nvar count = 1\ncount = count + 41\nreturn count\n} }");
}
static void test_val_reassignment(void) {
    invalid("class Main { static main(): i32 {\nval count = 1\ncount = 2\nreturn count\n} }",
            "val");
}
static void test_val_scalar_address(void) {
    invalid("class Main { static main() {\nval x = 1\nval p = &x\n*p = 2\n} }", "val");
}
static void test_val_pointer_mutates_pointee(void) {
    valid("class Main { static main(): i32 {\nvar x = 1\nval p = &x\n*p = 42\nreturn x\n} }");
}
static void test_val_object_mutation(void) {
    valid("class Box {\nvar value: i32\nset(value: i32): void { this.value = value }\n}\nclass "
          "Main { static main(): i32 {\nval box = Box { value: 1 }\n(&box).set(42)\nreturn "
          "box.value\n} }");
}
static void test_val_field_write(void) {
    invalid("class Box {\nval value: i32\nset(value: i32) { this.value = value }\n}", "val");
}
static void test_val_field_address(void) {
    invalid("class Box {\nval value: i32\nset() { val p = &this.value; *p = 2 }\n}", "val");
}
static void test_val_deferred_branches(void) {
    valid("class Main { static main(): i32 {\nval x: i32\nif true { x = 42 } else { x = 0 "
          "}\nreturn x\n} }");
}
static void test_val_possible_reassignment(void) {
    invalid(
        "class Main { static main(): i32 {\nval x: i32\nif true { x = 1 }\nx = 42\nreturn x\n} }",
        "val");
}
static void test_val_loop_reassignment(void) {
    invalid("class Main { static main() {\nval x: i32\nwhile true { x = 42 }\n} }", "val");
}
static void test_val_inside_loop(void) {
    valid("class Main { static main() {\nvar x = 0\nwhile x < 2 {\nval next = x + 1\nx = "
          "next\n}\n} }");
}
static void test_missing_inference_initializer(void) {
    invalid("class Main { static main() { var value } }", "type or initializer");
}
static void test_null_is_removed(void) {
    invalid("class Main { static main() { val value = null } }", "Optional");
}
static void test_expression_void(void) {
    valid("class Main { static output(): void = print_i32(42)\nstatic main(): i32 { Main.output(); "
          "return 0 } }");
}
static void test_expression_type_mismatch(void) {
    invalid("class Main { static main(): i32 = true }", "type mismatch");
}
static void test_same_line_requires_separator(void) {
    invalid("class Main { static main() { var x = 1 var y = 2 } }", "line break");
}
static void test_newline_operator_continuation(void) {
    valid("class Main { static main(): i32 {\nval x = 20 +\n22\nreturn x\n} }");
}
static void test_grouped_expression_continuation(void) {
    valid("class Main { static main(): i32 {\nval x = (20\n+ 22)\nreturn x\n} }");
}
static void test_multiline_call(void) {
    valid("class Main { static add(a: i32, b: i32): i32 = a + b\nstatic main(): i32 {\nreturn "
          "Main.add(\n20,\n22\n)\n} }");
}
static void test_multiline_member_chain(void) {
    valid("class Main { static answer(): i32 = 42\nstatic main(): i32 {\nreturn Main\n.answer()\n} "
          "}");
}
static void test_newline_starts_new_unary_statement(void) {
    valid("class Main { static main(): i32 {\nvar x = 1\n-x\nreturn x\n} }");
}
static void test_bare_return_line(void) {
    invalid("class Main { static main(): i32 {\nreturn\n42\n} }", "return a value");
}
static void test_void_bare_return(void) {
    valid("class Main { static main() {\nreturn\n} }");
}
static void test_comment_line_boundary(void) {
    valid("class Main { static main(): i32 {\nval x = 20 /* comment\n continued */ val y = "
          "22\nreturn x + y\n} }");
}
static void test_crlf(void) {
    valid("class Main {\r\nstatic main(): i32 {\r\nval x = 42\r\nreturn x\r\n}\r\n}");
}
static void test_generic_call_syntax(void) {
    valid("class Main { static id<T>(x: T): T = x\nstatic main(): i32 {\nval x = "
          "Main.id<i32>(42)\nreturn x\n} }");
}
static void test_match_arm_line_separators(void) {
    valid("class Main { static main(): i32 {\nval option = Optional.Some(42)\nreturn match option "
          "{\nSome(value) => value\nNone => 0\n}\n} }");
}
static void test_inferred_native_context(void) {
    valid("class Main { static answer(): Optional<i32> = Optional.Some(42)\nstatic main(): i32 "
          "{\nval answer = Main.answer()\nreturn match answer { Some(value) => value, None => 0 "
          "}\n} }");
}
static void test_val_match_initialization(void) {
    valid("class Main { static main(): i32 {\nval x: i32\nmatch true {\ntrue => x = 42\nfalse => x "
          "= 0\n}\nreturn x\n} }");
}
static void test_val_match_possible_assignment(void) {
    invalid("class Main { static main() {\nval x: i32\nmatch true {\ntrue => x = 42\nfalse => "
            "print_i32(0)\n}\nx = 1\n} }",
            "val");
}
static void test_val_assignment_after_returning_branch(void) {
    valid("class Main { static main(): i32 {\nval x: i32\nif true { x = 1; return x }\nx = "
          "42\nreturn x\n} }");
}
static void test_parameter_is_immutable(void) {
    invalid("class Main { static change(value: i32): i32 { value = 42; return value } }", "val");
}
static void test_expression_body_explicit_return_type(void) {
    invalid("class Main { static answer() = 42 }", "explicit return type");
}
static void test_group_closing_on_next_line(void) {
    valid("class Main { static main(): i32 {\nval x = (20\n) + 22\nreturn x\n} }");
}
static void test_native_constructor_inference_requires_context(void) {
    invalid("class Main { static main() { val empty = Optional.None } }", "context");
}
int main(void) {
    UNITY_BEGIN();
#define RUN(name) RUN_TEST(test_##name)
    RUN(val_match_initialization);
    RUN(val_match_possible_assignment);
    RUN(val_assignment_after_returning_branch);
    RUN(parameter_is_immutable);
    RUN(expression_body_explicit_return_type);
    RUN(group_closing_on_next_line);
    RUN(native_constructor_inference_requires_context);
    RUN(target_syntax);
    RUN(inferred_var);
    RUN(val_reassignment);
    RUN(val_scalar_address);
    RUN(val_pointer_mutates_pointee);
    RUN(val_object_mutation);
    RUN(val_field_write);
    RUN(val_field_address);
    RUN(val_deferred_branches);
    RUN(val_possible_reassignment);
    RUN(val_loop_reassignment);
    RUN(val_inside_loop);
    RUN(missing_inference_initializer);
    RUN(null_is_removed);
    RUN(expression_void);
    RUN(expression_type_mismatch);
    RUN(same_line_requires_separator);
    RUN(newline_operator_continuation);
    RUN(grouped_expression_continuation);
    RUN(multiline_call);
    RUN(multiline_member_chain);
    RUN(newline_starts_new_unary_statement);
    RUN(bare_return_line);
    RUN(void_bare_return);
    RUN(comment_line_boundary);
    RUN(crlf);
    RUN(generic_call_syntax);
    RUN(match_arm_line_separators);
    RUN(inferred_native_context);
    return UNITY_END();
}
